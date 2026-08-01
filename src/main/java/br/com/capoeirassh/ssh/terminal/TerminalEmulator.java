package br.com.capoeirassh.ssh.terminal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * xterm-256color terminal emulator.
 * Thread-safe: all public methods are synchronized.
 */
public class TerminalEmulator {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------
    public static final int DEFAULT_COLOR  = -1;
    public static final int MAX_SCROLLBACK = 10_000;
    /** Caps OSC/DCS/PM/APC string payload growth if a server never sends the terminator. */
    private static final int MAX_OSC_LEN   = 8_192;
    /** Caps CSI parameter count so a hostile server streaming endless ';' can't OOM the JVM. */
    private static final int MAX_CSI_PARAMS = 32;
    /** Caps a single CSI parameter value so a long digit run can't overflow int. */
    private static final int MAX_CSI_PARAM_VALUE = 65_535;

    private static final int[] PALETTE = buildPalette();

    // -----------------------------------------------------------------------
    // Parser states
    // -----------------------------------------------------------------------
    private enum State { NORMAL, ESC, ESC_HASH, CSI, OSC, OSC_ESC, CHARSET_G0, CHARSET_G1 }

    // -----------------------------------------------------------------------
    // Buffers
    // -----------------------------------------------------------------------
    private int rows;
    private int cols;

    private TerminalCell[][] primaryBuffer;
    private TerminalCell[][] alternateBuffer;
    private TerminalCell[][] activeBuffer;

    private final List<TerminalCell[]> scrollback = new ArrayList<>();

    // -----------------------------------------------------------------------
    // Cursor
    // -----------------------------------------------------------------------
    private int     cursorRow;
    private int     cursorCol;
    private boolean wrapPending = false;

    // Saved cursor (ESC 7 / ESC 8)
    private int          savedRow;
    private int          savedCol;
    private final TerminalCell savedAttrs = new TerminalCell();

    // Saved state for alternate buffer switch (ESC[?1049h / ESC[?1049l)
    private int          altSavedRow;
    private int          altSavedCol;
    private int          altSavedScrollTop;
    private int          altSavedScrollBottom;
    private boolean      altSavedG0LineDrawing;
    private boolean      altSavedG1LineDrawing;
    private boolean      altSavedUseG1;
    private boolean      altSavedAppCursorKeys;
    private final TerminalCell altSavedAttrs = new TerminalCell();

    // -----------------------------------------------------------------------
    // Scroll region (0-based, inclusive)
    // -----------------------------------------------------------------------
    private int scrollTop;
    private int scrollBottom;

    // -----------------------------------------------------------------------
    // Current SGR attributes (template for new cells)
    // -----------------------------------------------------------------------
    private final TerminalCell currentAttrs = new TerminalCell();

    // -----------------------------------------------------------------------
    // Parser state
    // -----------------------------------------------------------------------
    private State           state      = State.NORMAL;
    private final List<Integer> params = new ArrayList<>();
    private final StringBuilder oscBuffer = new StringBuilder();
    private boolean         csiPrivate       = false;
    private boolean         csiIntermediate  = false; // intermediate bytes 0x20-0x2F seen in CSI

    // -----------------------------------------------------------------------
    // Charset (G0 / G1)
    // -----------------------------------------------------------------------
    private boolean g0LineDrawing = false;
    private boolean g1LineDrawing = false;
    private boolean useG1         = false;

    // -----------------------------------------------------------------------
    // Terminal modes
    // -----------------------------------------------------------------------
    private boolean appCursorKeys  = false;
    private boolean cursorVisible  = true;
    /** DECSET 2004. When on, the program wants pasted text wrapped in ESC[200~ / ESC[201~ so it
     *  can tell a paste from typing — readline enables it by default, and without the markers a
     *  pasted newline is indistinguishable from Enter, so the shell runs each line on arrival
     *  instead of leaving the whole thing in the edit buffer. */
    private boolean bracketedPaste = false;
    /** DECOM (ESC[?6h). When set, CUP/HVP row coordinates are relative to the scrolling region's
     *  top margin instead of the screen, and the cursor is confined to the region. */
    private boolean originMode = false;
    private boolean altBufferActive = false;
    private int     altBufferDepth  = 0;   // nesting counter for apps that stack alt-screen

    // -----------------------------------------------------------------------
    // UTF-8 incremental decoder
    // -----------------------------------------------------------------------
    private int utf8Remaining  = 0;
    private int utf8Codepoint  = 0;


    // -----------------------------------------------------------------------
    // Listeners
    // -----------------------------------------------------------------------
    @FunctionalInterface public interface DataListener      { void onData(byte[] data); }
    @FunctionalInterface public interface ChangeListener   { void onChange(); }
    @FunctionalInterface public interface AltBufferListener { void onAltBufferChanged(boolean active); }
    @FunctionalInterface public interface TitleListener     { void onTitleChanged(String title); }
    /** Fired when the remote requests 80/132-column mode via DECCOLM. The emulator has already
     *  resized its own buffer by the time this runs; the listener's job is to resize the window
     *  to match. Called while holding this emulator's lock — implementations must hand off
     *  asynchronously (Display.asyncExec), never block. */
    @FunctionalInterface public interface ColumnModeListener { void onColumnModeChanged(int cols); }

    private DataListener      dataListener;
    private ChangeListener    changeListener;
    private AltBufferListener altBufferListener;
    private TitleListener     titleListener;
    private ColumnModeListener columnModeListener;
    /** Gates DECCOLM (see SessionInfo.allowColumnMode) — when false the sequence is ignored
     *  outright, so a remote can't resize the window. */
    private boolean allowColumnMode = false;

    /** Queued terminal responses (DSR, DA, XTWINOPS). Populated inside processBytes;
     *  drained by the caller after the lock is released to avoid I/O under the lock. */
    private final ConcurrentLinkedQueue<byte[]> pendingResponses = new ConcurrentLinkedQueue<>();

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------
    public TerminalEmulator(int cols, int rows) {
        this.cols = Math.max(1, cols);
        this.rows = Math.max(1, rows);
        initBuffers();
        resetState();
    }

    // -----------------------------------------------------------------------
    // Init
    // -----------------------------------------------------------------------
    private void initBuffers() {
        primaryBuffer   = allocBuffer(rows, cols);
        alternateBuffer = allocBuffer(rows, cols);
        activeBuffer    = primaryBuffer;
    }

    private static TerminalCell[][] allocBuffer(int r, int c) {
        TerminalCell[][] b = new TerminalCell[r][c];
        for (int i = 0; i < r; i++)
            for (int j = 0; j < c; j++)
                b[i][j] = new TerminalCell();
        return b;
    }

    private void resetState() {
        cursorRow    = 0;
        cursorCol    = 0;
        wrapPending  = false;
        scrollTop    = 0;
        scrollBottom = rows - 1;
        currentAttrs.clear();
        g0LineDrawing = false;
        g1LineDrawing = false;
        useG1         = false;
        appCursorKeys = false;
        cursorVisible = true;
        // RIS clears this too: the mode belongs to the program that asked for it, and a stale
        // "on" would make us wrap pastes in markers a plain shell would echo as literal text.
        bracketedPaste = false;
        originMode     = false;
    }

    // -----------------------------------------------------------------------
    // Listeners
    // -----------------------------------------------------------------------
    public synchronized void setDataListener(DataListener l)         { this.dataListener = l; }
    public synchronized void setChangeListener(ChangeListener l)     { this.changeListener = l; }
    public synchronized void setAltBufferListener(AltBufferListener l) { this.altBufferListener = l; }
    public synchronized void setTitleListener(TitleListener l)       { this.titleListener = l; }
    public synchronized void setColumnModeListener(ColumnModeListener l) { this.columnModeListener = l; }
    public synchronized void setAllowColumnMode(boolean on)          { this.allowColumnMode = on; }

    // -----------------------------------------------------------------------
    // Resize
    // -----------------------------------------------------------------------
    public synchronized void resize(int newCols, int newRows) {
        newCols = Math.max(1, newCols);
        newRows = Math.max(1, newRows);
        if (newCols == cols && newRows == rows) return;

        TerminalCell[][] newPrimary   = allocBuffer(newRows, newCols);
        TerminalCell[][] newAlternate = allocBuffer(newRows, newCols);

        int commonRows = Math.min(rows, newRows);
        int commonCols = Math.min(cols, newCols);
        for (int r = 0; r < commonRows; r++)
            for (int c = 0; c < commonCols; c++) {
                newPrimary[r][c].copyFrom(primaryBuffer[r][c]);
                newAlternate[r][c].copyFrom(alternateBuffer[r][c]);
            }

        this.cols = newCols;
        this.rows = newRows;
        primaryBuffer   = newPrimary;
        alternateBuffer = newAlternate;
        activeBuffer    = altBufferActive ? alternateBuffer : primaryBuffer;

        cursorRow    = Math.min(cursorRow,    rows - 1);
        cursorCol    = Math.min(cursorCol,    cols - 1);
        scrollTop    = 0;
        scrollBottom = rows - 1;
        wrapPending  = false;
    }

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------
    public synchronized void processBytes(byte[] data, int offset, int length) {
        for (int i = offset; i < offset + length; i++) processByte(data[i] & 0xFF);
        notifyChange();
    }

    public synchronized void processBytes(byte[] data) { processBytes(data, 0, data.length); }

    // -----------------------------------------------------------------------
    // Byte-level processing
    // -----------------------------------------------------------------------
    private void processByte(int b) {
        // OSC/DCS/SOS/PM/APC string content is opaque bytes until its terminator (BEL, ST, or
        // ESC \) — it must never be run through UTF-8 decoding or C1 interpretation below. A
        // multi-byte character embedded in one of these strings (e.g. a spinner glyph in an
        // OSC "set window title" sequence) has a UTF-8 continuation byte that can itself fall
        // in the 0x80-0x9F C1 range; decoding it as a real character called processCodePoint(),
        // which WRITES to the visible screen and moves the cursor — corrupting on-screen
        // content with fragments of a title that was only ever supposed to be swallowed.
        if (state == State.OSC || state == State.OSC_ESC) { processOscByte(b); return; }

        if (utf8Remaining > 0) {
            if ((b & 0xC0) == 0x80) {
                utf8Codepoint = (utf8Codepoint << 6) | (b & 0x3F);
                if (--utf8Remaining == 0) processCodePoint(utf8Codepoint);
                return;
            }
            utf8Remaining = 0; // invalid — discard
        }
        // C1 controls (0x80-0x9F): 8-bit equivalents of ESC + letter.
        // These overlap the UTF-8 continuation-byte range, so handle them before UTF-8.
        if (b >= 0x80 && b <= 0x9F) { processC1(b); return; }
        if      ((b & 0x80) == 0) processASCII(b);
        else if ((b & 0xE0) == 0xC0) { utf8Codepoint = b & 0x1F; utf8Remaining = 1; }
        else if ((b & 0xF0) == 0xE0) { utf8Codepoint = b & 0x0F; utf8Remaining = 2; }
        else if ((b & 0xF8) == 0xF0) { utf8Codepoint = b & 0x07; utf8Remaining = 3; }
    }

    /** Buffers one raw byte of an OSC/DCS/SOS/PM/APC string, recognizing only its terminators
     *  (BEL or ESC \) — see the caller for why this must run before any UTF-8/C1 interpretation.
     *  Deliberately does NOT treat the raw 8-bit ST byte (0x9C) as a terminator here: 0x9C is
     *  also a valid UTF-8 continuation byte (10011100, inside the 0x80-0xBF range), so any
     *  multi-byte character embedded in the string — e.g. a spinner glyph — whose 2nd or 3rd
     *  byte happens to equal 0x9C would otherwise end the OSC mid-string. Everything after that
     *  false terminator would then be parsed as normal text and printed straight onto the
     *  screen at the cursor's position (a real, reproduced bug: an OSC 0 title containing "✳ "
     *  before its text leaked "Ativar auto mode permanentemente no Claude Code" onto the grid).
     *  BEL (0x07) and ESC (0x1B) are both below 0x80 and can never appear as a UTF-8 continuation
     *  byte, so they remain unambiguous terminators. */
    private void processOscByte(int b) {
        if (state == State.OSC_ESC) {
            if (b == '\\') finishOsc();
            else           { state = State.OSC; if (oscBuffer.length() < MAX_OSC_LEN) oscBuffer.append((char) b); }
            return;
        }
        if      (b == 0x1B) state = State.OSC_ESC;
        else if (b == 0x07) finishOsc();
        else if (oscBuffer.length() < MAX_OSC_LEN) oscBuffer.append((char) b);
    }

    /** Called on every OSC terminator (BEL, ST, or ESC \). Extracts the title text from a
     *  "set window/icon title" sequence (Ps 0, 1, or 2 — see ECMA-48 / XTerm ctlseqs) and hands
     *  it to the title listener; any other OSC (color queries, hyperlinks, clipboard, etc.) is
     *  still just swallowed, same as before. */
    private void finishOsc() {
        state = State.NORMAL;
        String raw = oscBuffer.toString();
        oscBuffer.setLength(0);
        int sep = raw.indexOf(';');
        if (sep > 0 && titleListener != null) {
            String ps = raw.substring(0, sep);
            if (ps.equals("0") || ps.equals("2")) {
                // oscBuffer holds one raw byte per char (0-255), never UTF-8-decoded — harmless
                // while this buffer was only ever discarded, but the title text can contain
                // accented characters or icon glyphs, so decode it properly before surfacing it.
                byte[] bytes = new byte[raw.length() - sep - 1];
                for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) raw.charAt(sep + 1 + i);
                titleListener.onTitleChanged(new String(bytes, StandardCharsets.UTF_8));
            }
        }
    }

    private void processC1(int b) {
        switch (b) {
            case 0x84 -> ind();
            case 0x85 -> nel();
            case 0x8D -> ri();
            case 0x9B -> { state = State.CSI; params.clear(); csiPrivate = false; csiIntermediate = false; }
            case 0x9C -> finishOsc();  // ST — terminates any open string
            case 0x90, 0x98, 0x9D, 0x9E, 0x9F -> { state = State.OSC; oscBuffer.setLength(0); } // DCS/SOS/OSC/PM/APC
            default -> {}
        }
    }

    private void processASCII(int b) {
        // ESC always (re)starts an escape sequence, regardless of current parser state.
        // CAN (0x18) and SUB (0x1A) also cancel the current sequence.
        if (b == 0x1B) { state = State.ESC; return; }
        if (b == 0x18 || b == 0x1A) { state = State.NORMAL; return; }

        if (state == State.NORMAL) {
            switch (b) {
                case 0x07 -> {}
                case 0x08 -> { if (cursorCol > 0) { cursorCol--; wrapPending = false; } }
                case 0x09 -> advanceTab();
                case 0x0A, 0x0B, 0x0C -> lineFeed();
                case 0x0D -> { cursorCol = 0; wrapPending = false; }
                case 0x0E -> useG1 = true;
                case 0x0F -> useG1 = false;
                default   -> { if (b >= 0x20) processCodePoint(b); }
            }
        } else if (state == State.ESC) {
            processEscape(b);
        } else if (state == State.ESC_HASH) {
            // Only DECALN is acted on; DECDHL/DECDWL (3,4,5,6) and anything else are consumed
            // and ignored — the point is that the final byte must never reach the screen.
            state = State.NORMAL;
            if (b == '8') decaln();
        } else if (state == State.CSI) {
            processCSI(b);
        } else if (state == State.CHARSET_G0) {
            g0LineDrawing = (b == '0');
            state = State.NORMAL;
        } else if (state == State.CHARSET_G1) {
            g1LineDrawing = (b == '0');
            state = State.NORMAL;
        }
    }

    private void processCodePoint(int cp) {
        // Reject what the incremental UTF-8 decoder can produce from malformed input: values
        // past the Unicode maximum, and lone surrogates (never valid as UTF-8). Storing either
        // would make Character.toChars() throw on the render thread — i.e. a malformed or
        // hostile byte stream could kill the terminal tab.
        if (cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) return;

        boolean lineDrawing = (useG1 ? g1LineDrawing : g0LineDrawing) && cp >= 0x60 && cp <= 0x7E;
        // Stored as a code point, so characters outside the BMP survive instead of becoming '?'.
        int ch = lineDrawing ? ACS_MAP[cp - 0x60] : cp;

        // A double-width glyph occupies two columns. The remote side counts it as two when
        // positioning the cursor, so we must too — otherwise every wide character (CJK, emoji,
        // the box-drawing/status glyphs modern CLIs use) shifts our idea of the cursor column
        // one further left than the server's, and subsequent ESC[K / cursor addressing lands in
        // the wrong place, leaving stale text on screen. Line-drawing ACS output is always
        // single-width regardless of the mapped glyph.
        int width = lineDrawing ? 1 : charWidth(cp);
        // Zero-width (combining marks, variation selectors, ZWJ): consume no column and leave
        // the previously written cell alone. Rendering the mark itself would need per-cell
        // grapheme clusters, which this one-code-point-per-cell model doesn't have — but silently
        // dropping it keeps the cursor in sync, which is what matters for screen correctness.
        if (width == 0) return;

        if (wrapPending) {
            wrapPending = false;
            cursorCol   = 0;
            advanceLine();
        }
        // A wide glyph that no longer fits in the current line wraps whole, rather than being
        // split across the boundary.
        if (width == 2 && cursorCol == cols - 1) {
            eraseCell(activeBuffer[cursorRow][cursorCol]);
            cursorCol = 0;
            advanceLine();
        }

        writeCell(cursorRow, cursorCol, ch);
        if (width == 2) {
            writeCell(cursorRow, cursorCol + 1, ' ');
            if (cursorCol + 1 < cols) activeBuffer[cursorRow][cursorCol + 1].wideTrailer = true;
        }

        if (cursorCol + width <= cols - 1) cursorCol += width;
        else wrapPending = true;
    }

    /**
     * Columns occupied by a code point: 2 for East Asian Wide/Fullwidth characters and emoji,
     * 0 for combining marks, 1 otherwise. This is the {@code wcwidth} behaviour every terminal
     * and every well-behaved remote program assumes, so it must match theirs for cursor
     * positioning to stay in sync.
     */
    static int charWidth(int cp) {
        // Combining marks / zero-width — attach to the previous cell, consume no column.
        if (cp == 0x200B || cp == 0xFEFF
            || (cp >= 0x0300 && cp <= 0x036F)   // combining diacritical marks
            || (cp >= 0x200C && cp <= 0x200F)   // ZWNJ/ZWJ, LRM/RLM
            || (cp >= 0xFE00 && cp <= 0xFE0F))  // variation selectors
            return 0;

        if (cp < 0x1100) return 1;              // fast path: Latin/Greek/Cyrillic/etc.

        if ((cp >= 0x1100 && cp <= 0x115F)      // Hangul Jamo initial consonants
            || (cp >= 0x2E80 && cp <= 0x303E)   // CJK radicals, Kangxi, CJK symbols
            || (cp >= 0x3041 && cp <= 0x33FF)   // Hiragana, Katakana, Hangul Compat, CJK compat
            || (cp >= 0x3400 && cp <= 0x4DBF)   // CJK Unified Ext A
            || (cp >= 0x4E00 && cp <= 0x9FFF)   // CJK Unified Ideographs
            || (cp >= 0xA000 && cp <= 0xA4CF)   // Yi
            || (cp >= 0xAC00 && cp <= 0xD7A3)   // Hangul syllables
            || (cp >= 0xF900 && cp <= 0xFAFF)   // CJK compatibility ideographs
            || (cp >= 0xFE10 && cp <= 0xFE19)   // vertical forms
            || (cp >= 0xFE30 && cp <= 0xFE6F)   // CJK compatibility forms
            || (cp >= 0xFF00 && cp <= 0xFF60)   // fullwidth forms
            || (cp >= 0xFFE0 && cp <= 0xFFE6))  // fullwidth signs
            return 2;

        // Emoji and pictographs (the ranges modern CLIs use for status indicators).
        if ((cp >= 0x1F300 && cp <= 0x1F64F)
            || (cp >= 0x1F680 && cp <= 0x1F6FF)
            || (cp >= 0x1F900 && cp <= 0x1F9FF)
            || (cp >= 0x1FA70 && cp <= 0x1FAFF)
            || (cp >= 0x20000 && cp <= 0x3FFFD)) // CJK Unified Ext B-F
            return 2;

        return 1;
    }

    private void processEscape(int b) {
        state = State.NORMAL;
        switch (b) {
            case '[' -> { state = State.CSI; params.clear(); csiPrivate = false; csiIntermediate = false; }
            case ']' -> { state = State.OSC; oscBuffer.setLength(0); }
            // String sequences — consume until ST (ESC \) using the OSC state machine
            case 'P', '_', '^', 'X', 'k' -> { state = State.OSC; oscBuffer.setLength(0); }
            case '(' -> state = State.CHARSET_G0;
            case ')' -> state = State.CHARSET_G1;
            // ESC # <digit> — DEC line-size / alignment sequences. Must consume the digit in a
            // dedicated state: without it the '#' was dropped, the parser fell back to NORMAL,
            // and the digit was printed as literal text on screen.
            case '#' -> state = State.ESC_HASH;
            case '7' -> saveCursor();
            case '8' -> restoreCursor();
            case 'c' -> ris();
            case 'D' -> ind();
            case 'E' -> nel();
            case 'M' -> ri();
        }
    }

    private void processCSI(int b) {
        // Private-mode prefix characters (must come before digits)
        if (b == '?' || b == '>' || b == '<' || b == '=') { csiPrivate = true; return; }
        // Intermediate bytes (SP ! " # $ % & ' ( ) * + , - . /)
        // These are part of the sequence — buffer them, don't exit CSI
        if (b >= 0x20 && b <= 0x2F) { csiIntermediate = true; return; }
        if (b >= '0' && b <= '9') {
            if (params.isEmpty()) params.add(0);
            int idx = params.size() - 1;
            // Clamp accumulation so a long digit run can't overflow int into a negative value.
            params.set(idx, (int) Math.min((long) params.get(idx) * 10 + (b - '0'), MAX_CSI_PARAM_VALUE));
            return;
        }
        // Cap the parameter list — a hostile server streaming endless ';' must not grow it
        // without bound (would exhaust the heap and crash the whole app). Real terminals
        // ignore excess parameters, so dropping them past the cap is spec-compatible.
        if (b == ';') { if (params.size() < MAX_CSI_PARAMS) params.add(0); return; }

        state = State.NORMAL;

        // Sequences with intermediate bytes (e.g. DECSCUSR \e[N SP q) — just ignore
        if (csiIntermediate) { csiIntermediate = false; return; }

        if (csiPrivate) { processCSIPrivate(b); return; }

        int p1 = params.isEmpty()       ? 0 : params.get(0);
        int p2 = params.size() < 2      ? 0 : params.get(1);

        switch ((char) b) {
            case 'A' -> moveCursorInRegion(cursorRow - Math.max(1, p1), cursorCol);
            case 'B' -> moveCursorInRegion(cursorRow + Math.max(1, p1), cursorCol);
            case 'C' -> moveCursor(cursorRow, cursorCol + Math.max(1, p1));
            case 'D' -> moveCursor(cursorRow, cursorCol - Math.max(1, p1));
            case 'E' -> moveCursor(cursorRow + Math.max(1, p1), 0);
            case 'F' -> moveCursor(cursorRow - Math.max(1, p1), 0);
            case 'G' -> moveCursor(cursorRow, Math.max(1, p1) - 1);
            case 'H', 'f' -> cup(Math.max(1, p1) - 1, Math.max(1, p2) - 1);
            case 'J' -> eraseDisplay(p1);
            case 'K' -> eraseLine(p1);
            case 'L' -> insertLines(Math.max(1, p1));
            case 'M' -> deleteLines(Math.max(1, p1));
            case 'P' -> deleteChars(Math.max(1, p1));
            case 'X' -> eraseChars(Math.max(1, p1));
            case '@' -> insertChars(Math.max(1, p1));
            case 'S' -> scrollUp(Math.max(1, p1));
            case 'T' -> scrollDown(Math.max(1, p1));
            case 'd' -> moveCursor(Math.max(1, p1) - 1, cursorCol);
            case 'm' -> processSGR();
            case 'n' -> processDSR(p1);
            case 'r' -> decstbm();
            case 's' -> saveCursor();
            case 'u' -> restoreCursor();
            case 'c' -> sendDA();
            case 't' -> processXtwinops(p1);
        }
    }

    private void processCSIPrivate(int b) {
        int p1 = params.isEmpty() ? 0 : params.get(0);
        if (b == 'h') {
            switch (p1) {
                case 1           -> appCursorKeys = true;
                case 3           -> deccolm(true);   // 132-column mode
                case 6           -> decom(true);     // origin mode
                case 12          -> {} // cursor blink on  — ignored
                case 25          -> cursorVisible = true;
                case 47, 1047    -> activateAltBuffer(true);
                case 1049        -> activateAltBuffer(true);
                case 2004        -> bracketedPaste = true;
                // commonly sent by vim/bash — safe to ignore
                case 1000, 1002,
                     1003, 1006  -> {} // mouse tracking
                case 1004        -> {} // focus events
            }
        } else if (b == 'l') {
            switch (p1) {
                case 1           -> appCursorKeys = false;
                case 3           -> deccolm(false);  // 80-column mode
                case 6           -> decom(false);    // origin mode off
                case 4           -> {} // smooth scroll — ignored
                case 12          -> {} // cursor blink off — ignored
                case 25          -> cursorVisible = false;
                case 47, 1047    -> deactivateAltBuffer(true);
                case 1049        -> deactivateAltBuffer(true);
                case 2004        -> bracketedPaste = false;
                case 1000, 1002,
                     1003, 1006  -> {} // mouse tracking
                case 1004        -> {} // focus events
            }
        }
    }

    // -----------------------------------------------------------------------
    // VT sequences
    // -----------------------------------------------------------------------
    private void ind() {
        if (cursorRow == scrollBottom) scrollRegion(1);
        else moveCursor(cursorRow + 1, cursorCol);
    }

    private void nel() { cursorCol = 0; ind(); }

    private void ri() {
        if (cursorRow == scrollTop) scrollRegion(-1);
        else moveCursor(cursorRow - 1, cursorCol);
    }

    private void ris() {
        for (TerminalCell[] row : primaryBuffer)   for (TerminalCell c : row) c.clear();
        for (TerminalCell[] row : alternateBuffer) for (TerminalCell c : row) c.clear();
        scrollback.clear();
        if (altBufferActive) { altBufferActive = false; activeBuffer = primaryBuffer; }
        altBufferDepth = 0;
        resetState();
    }

    /**
     * DECALN (ESC # 8) — Screen Alignment Pattern: fills the whole visible screen with 'E',
     * resets the scrolling region to the full screen, and homes the cursor. A DEC diagnostic
     * for checking screen alignment, but terminal test suites lean on it as a bulk "paint every
     * cell" primitive — vttest fills the screen with E and then carves the pattern it wants back
     * out with erase operations, so a no-op here doesn't just lose the E's, it also leaves
     * whatever was on screen before showing through wherever the test doesn't draw.
     * Fills with default attributes (not the current SGR), matching xterm.
     */
    private void decaln() {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                TerminalCell cell = activeBuffer[r][c];
                cell.clear();
                cell.character = 'E';
            }
        }
        scrollTop    = 0;
        scrollBottom = rows - 1;
        cursorRow    = 0;
        cursorCol    = 0;
        wrapPending  = false;
    }

    /**
     * DECCOLM (ESC[?3h / ESC[?3l) — switch between 132- and 80-column mode. On a real VT100 this
     * changed the hardware column count; here it has to resize the window, so it is gated behind
     * a per-session option (see {@link #setAllowColumnMode}) and simply ignored when off, which
     * is how xterm treats it by default too.
     *
     * The buffer is resized SYNCHRONOUSLY here rather than waiting for the window resize to come
     * back round: a program that sends DECCOLM starts drawing at the new width immediately, and
     * the window resize is asynchronous (and debounced), so deferring would send that first
     * screenful into a buffer still at the old width and wrap it.
     *
     * Per DEC STD 070 the mode change also clears the screen, homes the cursor and resets the
     * scrolling region — programs rely on that instead of sending a separate clear.
     */
    private void deccolm(boolean wide) {
        if (!allowColumnMode) return;
        int target = wide ? 132 : 80;
        resize(target, rows);
        for (TerminalCell[] row : activeBuffer) for (TerminalCell c : row) c.clear();
        cursorRow    = 0;
        cursorCol    = 0;
        wrapPending  = false;
        scrollTop    = 0;
        scrollBottom = rows - 1;
        if (columnModeListener != null) columnModeListener.onColumnModeChanged(target);
    }

    /**
     * DECOM (ESC[?6h / ESC[?6l) — origin mode. Switching it either way homes the cursor, and
     * "home" itself depends on the mode: the top margin of the scrolling region when set, the
     * top of the screen when reset.
     */
    private void decom(boolean on) {
        originMode = on;
        cursorRow   = on ? scrollTop : 0;
        cursorCol   = 0;
        wrapPending = false;
    }

    private void decstbm() {
        int top = params.isEmpty()       ? 1     : params.get(0);
        int bot = params.size() < 2      ? rows  : params.get(1);
        scrollTop    = Math.max(0, top - 1);
        scrollBottom = Math.min(rows - 1, bot - 1);
        if (scrollTop >= scrollBottom) { scrollTop = 0; scrollBottom = rows - 1; }
        // DECSTBM homes the cursor; in origin mode home is the region's top margin.
        cursorRow   = originMode ? scrollTop : 0;
        cursorCol   = 0;
        wrapPending = false;
    }

    private void eraseDisplay(int mode) {
        switch (mode) {
            case 0 -> {
                clearLineRange(cursorRow, cursorCol, cols);
                for (int r = cursorRow + 1; r < rows; r++) clearLineRange(r, 0, cols);
            }
            case 1 -> {
                for (int r = 0; r < cursorRow; r++) clearLineRange(r, 0, cols);
                clearLineRange(cursorRow, 0, cursorCol + 1);
            }
            case 2 -> { for (int r = 0; r < rows; r++) clearLineRange(r, 0, cols); }
            case 3 -> { for (int r = 0; r < rows; r++) clearLineRange(r, 0, cols); scrollback.clear(); }
        }
    }

    private void eraseLine(int mode) {
        switch (mode) {
            case 0 -> clearLineRange(cursorRow, cursorCol, cols);
            case 1 -> clearLineRange(cursorRow, 0, cursorCol + 1);
            case 2 -> clearLineRange(cursorRow, 0, cols);
        }
    }

    private void insertLines(int n) {
        // Beyond this many repeats, [cursorRow, scrollBottom] is already fully
        // cleared and further iterations are no-ops — clamping avoids an
        // attacker-supplied repeat count (up to ~2^31) spinning the parser
        // thread (and, via the shared lock, the UI thread) for a long time.
        n = Math.min(n, scrollBottom - cursorRow + 1);
        for (int i = 0; i < n; i++) {
            for (int r = scrollBottom; r > cursorRow; r--)
                for (int c = 0; c < cols; c++) activeBuffer[r][c].copyFrom(activeBuffer[r - 1][c]);
            clearLineRange(cursorRow, 0, cols);
        }
    }

    private void deleteLines(int n) {
        // See insertLines() — same clamp rationale, mirrored for the delete direction.
        n = Math.min(n, scrollBottom - cursorRow + 1);
        for (int i = 0; i < n; i++) {
            for (int r = cursorRow; r < scrollBottom; r++)
                for (int c = 0; c < cols; c++) activeBuffer[r][c].copyFrom(activeBuffer[r + 1][c]);
            clearLineRange(scrollBottom, 0, cols);
        }
    }

    private void deleteChars(int n) {
        // Clamp to the remainder of the line — a remote-supplied count larger than
        // (cols - cursorCol) would otherwise drive `cols - n` negative below and
        // throw ArrayIndexOutOfBoundsException, killing the session.
        n = Math.min(n, cols - cursorCol);
        if (n <= 0) return;
        // Must use copyFrom (deep copy), not System.arraycopy (shallow reference copy).
        // arraycopy aliases cell objects, causing later writes to one cell to corrupt others.
        for (int c = cursorCol; c < cols - n; c++)
            activeBuffer[cursorRow][c].copyFrom(activeBuffer[cursorRow][c + n]);
        for (int c = cols - n; c < cols; c++) eraseCell(activeBuffer[cursorRow][c]);
    }

    private void eraseChars(int n) {
        for (int c = cursorCol; c < cursorCol + n && c < cols; c++)
            eraseCell(activeBuffer[cursorRow][c]);
    }

    private void insertChars(int n) {
        // Clamp to the remainder of the line — a remote-supplied count near INT_MAX
        // would otherwise make `cursorCol + n` overflow to a negative value, the loop
        // then index a large negative column and throw ArrayIndexOutOfBoundsException,
        // killing the session. Mirrors the clamp in deleteChars().
        n = Math.min(n, cols - cursorCol);
        if (n <= 0) return;
        for (int c = cols - 1; c >= cursorCol + n; c--)
            activeBuffer[cursorRow][c].copyFrom(activeBuffer[cursorRow][c - n]);
        for (int c = cursorCol; c < cursorCol + n && c < cols; c++)
            eraseCell(activeBuffer[cursorRow][c]);
    }

    private void scrollUp(int n)   { scrollRegion(n); }
    private void scrollDown(int n) { scrollRegion(-n); }

    private void processDSR(int n) {
        switch (n) {
            case 5 -> send("\033[0n");
            case 6 -> send("\033[" + (cursorRow + 1) + ";" + (cursorCol + 1) + "R");
        }
    }

    private void sendDA() { send("\033[?1;2c"); }

    private void processXtwinops(int op) {
        if (op == 18) send("\033[8;" + rows + ";" + cols + "t");
    }

    // -----------------------------------------------------------------------
    // SGR
    // -----------------------------------------------------------------------
    private void processSGR() {
        if (params.isEmpty()) params.add(0);
        for (int i = 0; i < params.size(); i++) {
            int p = params.get(i);
            switch (p) {
                case 0  -> currentAttrs.resetAttrs();
                case 1  -> currentAttrs.bold      = true;
                case 4  -> currentAttrs.underline = true;
                case 5  -> currentAttrs.blink     = true;
                case 7  -> currentAttrs.reverse   = true;
                case 22 -> currentAttrs.bold      = false;
                case 24 -> currentAttrs.underline = false;
                case 25 -> currentAttrs.blink     = false;
                case 27 -> currentAttrs.reverse   = false;
                case 39 -> currentAttrs.fgColor   = DEFAULT_COLOR;
                case 49 -> currentAttrs.bgColor   = DEFAULT_COLOR;
                default -> {
                    if      (p >= 30  && p <= 37)  currentAttrs.fgColor = p - 30;
                    else if (p >= 40  && p <= 47)  currentAttrs.bgColor = p - 40;
                    else if (p >= 90  && p <= 97)  currentAttrs.fgColor = p - 90  + 8;
                    else if (p >= 100 && p <= 107) currentAttrs.bgColor = p - 100 + 8;
                    else if (p == 38 && i + 2 < params.size() && params.get(i + 1) == 5)
                        { currentAttrs.fgColor = params.get(i + 2); i += 2; }
                    else if (p == 48 && i + 2 < params.size() && params.get(i + 1) == 5)
                        { currentAttrs.bgColor = params.get(i + 2); i += 2; }
                    else if (p == 38 && i + 4 < params.size() && params.get(i + 1) == 2)
                        { currentAttrs.fgColor = 256 + encodeRGB(params.get(i+2),params.get(i+3),params.get(i+4)); i += 4; }
                    else if (p == 48 && i + 4 < params.size() && params.get(i + 1) == 2)
                        { currentAttrs.bgColor = 256 + encodeRGB(params.get(i+2),params.get(i+3),params.get(i+4)); i += 4; }
                }
            }
        }
    }

    private static int encodeRGB(int r, int g, int b) {
        return ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    // -----------------------------------------------------------------------
    // Alternate screen buffer
    // -----------------------------------------------------------------------
    private void activateAltBuffer(boolean saveState) {
        altBufferDepth++;
        if (altBufferActive) {
            // Nested activation (e.g. YaST inside MC): just clear the alt buffer
            // so the inner app gets a clean slate. Don't touch the saved primary state.
            for (TerminalCell[] row : alternateBuffer) for (TerminalCell c : row) c.clear();
            cursorRow = 0; cursorCol = 0; wrapPending = false;
            scrollTop = 0; scrollBottom = rows - 1;
            g0LineDrawing = false; g1LineDrawing = false; useG1 = false;
            currentAttrs.clear();
            notifyAltBufferChanged();
            return;
        }
        if (saveState) {
            altSavedRow          = cursorRow;
            altSavedCol          = cursorCol;
            altSavedScrollTop    = scrollTop;
            altSavedScrollBottom = scrollBottom;
            altSavedG0LineDrawing = g0LineDrawing;
            altSavedG1LineDrawing = g1LineDrawing;
            altSavedUseG1        = useG1;
            altSavedAppCursorKeys = appCursorKeys;
            altSavedAttrs.copyFrom(currentAttrs);
        }
        for (TerminalCell[] row : alternateBuffer) for (TerminalCell c : row) c.clear();
        altBufferActive = true;
        activeBuffer    = alternateBuffer;
        cursorRow = 0; cursorCol = 0; wrapPending = false;
        scrollTop = 0; scrollBottom = rows - 1;
        g0LineDrawing = false; g1LineDrawing = false; useG1 = false;
        currentAttrs.clear();
        notifyAltBufferChanged();
    }

    private void deactivateAltBuffer(boolean restoreState) {
        if (!altBufferActive) return;
        if (altBufferDepth > 1) {
            // Nested deactivation (inner app like YaST exiting): stay in alt buffer.
            // Do NOT clear the alt buffer here. ncurses in the outer app (MC) holds
            // its own physical-screen model that reflects whatever the inner app drew.
            // MC will do a differential update from that state — only changing cells
            // that differ. If we cleared the buffer, cells that ncurses thinks are
            // already correct would never be redrawn, leaving blank/corrupt cells.
            // Leaving the inner app's content intact lets the differential update work.
            altBufferDepth--;
            if (restoreState) {
                cursorRow = altSavedRow; cursorCol = altSavedCol;
                scrollTop = altSavedScrollTop; scrollBottom = altSavedScrollBottom;
                g0LineDrawing = altSavedG0LineDrawing; g1LineDrawing = altSavedG1LineDrawing;
                useG1 = altSavedUseG1; appCursorKeys = altSavedAppCursorKeys;
                currentAttrs.copyFrom(altSavedAttrs);
            } else {
                scrollTop = 0; scrollBottom = rows - 1;
                g0LineDrawing = false; g1LineDrawing = false; useG1 = false;
                currentAttrs.clear();
            }
            wrapPending = false;
            notifyAltBufferChanged();
            return;
        }
        altBufferDepth = 0;
        altBufferActive = false;
        activeBuffer    = primaryBuffer;
        if (restoreState) {
            cursorRow    = altSavedRow;
            cursorCol    = altSavedCol;
            scrollTop    = altSavedScrollTop;
            scrollBottom = altSavedScrollBottom;
            g0LineDrawing = altSavedG0LineDrawing;
            g1LineDrawing = altSavedG1LineDrawing;
            useG1         = altSavedUseG1;
            appCursorKeys = altSavedAppCursorKeys;
            currentAttrs.copyFrom(altSavedAttrs);
        } else {
            scrollTop = 0; scrollBottom = rows - 1;
            g0LineDrawing = false; g1LineDrawing = false; useG1 = false;
            currentAttrs.clear();
        }
        wrapPending = false;
        notifyAltBufferChanged();
    }

    // -----------------------------------------------------------------------
    // Cursor & scroll helpers
    // -----------------------------------------------------------------------
    private void moveCursor(int row, int col) {
        cursorRow   = Math.max(0, Math.min(rows - 1, row));
        cursorCol   = Math.max(0, Math.min(cols - 1, col));
        wrapPending = false;
    }

    /**
     * CUP/HVP. {@code row} arrives 0-based and screen-relative; under DECOM it is instead
     * relative to the scrolling region's top margin, and the cursor cannot be placed outside
     * the region.
     */
    private void cup(int row, int col) {
        if (originMode) moveCursor(Math.min(scrollTop + row, scrollBottom), col);
        else            moveCursor(row, col);
    }

    /** Vertical cursor movement (CUU/CUD): under DECOM the cursor stays inside the scrolling
     *  region rather than running to the screen edges. */
    private void moveCursorInRegion(int row, int col) {
        if (originMode) row = Math.max(scrollTop, Math.min(scrollBottom, row));
        moveCursor(row, col);
    }

    private void saveCursor() {
        savedRow = cursorRow; savedCol = cursorCol;
        savedAttrs.copyFrom(currentAttrs);
    }

    private void restoreCursor() {
        cursorRow   = Math.min(savedRow, rows - 1);
        cursorCol   = Math.min(savedCol, cols - 1);
        currentAttrs.copyFrom(savedAttrs);
        wrapPending = false;
    }

    private void lineFeed() {
        if (cursorRow == scrollBottom) scrollRegion(1);
        else cursorRow = Math.min(cursorRow + 1, rows - 1);
        wrapPending = false;
    }

    private void advanceLine() { lineFeed(); }

    private void advanceTab() {
        cursorCol   = Math.min(((cursorCol / 8) + 1) * 8, cols - 1);
        wrapPending = false;
    }

    /**
     * Scrolls the scroll region by {@code n} lines.
     * Positive = scroll up (new blank line at bottom);
     * Negative = scroll down (new blank line at top).
     */
    private void scrollRegion(int n) {
        // Clamp the repeat count to the scroll-region height: beyond that the region
        // is already fully blanked and further iterations are no-ops. An unclamped
        // remote-supplied count (up to ~2 billion via CSI S/T) would otherwise spin
        // this O(rows*cols) loop for a very long time while holding the emulator's
        // synchronized lock, freezing the UI thread. Mirrors insertLines/deleteLines.
        int regionHeight = scrollBottom - scrollTop + 1;
        if (n > 0) {
            n = Math.min(n, regionHeight);
            for (int i = 0; i < n; i++) {
                // Feed scrollback only from primary buffer when the region covers the whole screen
                if (!altBufferActive && scrollTop == 0 && scrollBottom == rows - 1) {
                    TerminalCell[] saved = new TerminalCell[cols];
                    for (int c = 0; c < cols; c++) saved[c] = new TerminalCell(primaryBuffer[0][c]);
                    scrollback.add(saved);
                    if (scrollback.size() > MAX_SCROLLBACK) scrollback.remove(0);
                }
                for (int r = scrollTop; r < scrollBottom; r++)
                    for (int c = 0; c < cols; c++) activeBuffer[r][c].copyFrom(activeBuffer[r + 1][c]);
                clearLineRange(scrollBottom, 0, cols);
            }
        } else {
            n = Math.min(-n, regionHeight);
            for (int i = 0; i < n; i++) {
                for (int r = scrollBottom; r > scrollTop; r--)
                    for (int c = 0; c < cols; c++) activeBuffer[r][c].copyFrom(activeBuffer[r - 1][c]);
                clearLineRange(scrollTop, 0, cols);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Write / clear helpers
    // -----------------------------------------------------------------------
    private void writeCell(int row, int col, int ch) {
        if (row < 0 || row >= rows || col < 0 || col >= cols) return;
        TerminalCell cell = activeBuffer[row][col];
        cell.character = ch;
        cell.fgColor   = currentAttrs.fgColor;
        cell.bgColor   = currentAttrs.bgColor;
        cell.bold      = currentAttrs.bold;
        cell.underline = currentAttrs.underline;
        cell.reverse   = currentAttrs.reverse;
        cell.blink     = currentAttrs.blink;
        // Caller sets this afterward for the trailer half of a wide glyph; any ordinary write
        // must clear a stale flag left by a wide glyph previously occupying this column.
        cell.wideTrailer = false;
    }

    private void clearLineRange(int row, int colStart, int colEnd) {
        if (row < 0 || row >= rows) return;
        for (int c = colStart; c < colEnd && c < cols; c++)
            eraseCell(activeBuffer[row][c]);
    }

    /**
     * Erases a single cell using the current SGR background colour.
     * VT100 spec (background-color-erase): erase operations fill with space + the CURRENT SGR
     * background, unconditionally. That includes clearing the cell back to DEFAULT_COLOR when
     * the current background is default — an erase after \e[0m must leave no colour behind.
     *
     * An earlier revision kept a cell's existing colour here when the alt buffer was active and
     * the current background was default, to hide "black stripes" seen in ncurses TUI apps
     * (YaST). That was a symptom-level patch: the real cause, found hours later, was deleteChars
     * using System.arraycopy on the TerminalCell[] — copying references, so cells in a row
     * aliased one object and writes corrupted their neighbours (fixed in build 30, the deep-copy
     * copyFrom loop). ncurses/MC lean heavily on deleteChars, which is why the corruption showed
     * up there. With that root cause gone the workaround was obsolete, and it actively broke
     * modern TUI apps: an erased cell kept its old background, leaving solid coloured blocks
     * stuck to screen positions, smeared further by scrollRegion() erasing rows that still held
     * their previous cells.
     */
    private void eraseCell(TerminalCell cell) {
        cell.character   = ' ';
        cell.wideTrailer = false;
        cell.fgColor     = DEFAULT_COLOR;
        cell.bgColor     = currentAttrs.bgColor;
        cell.bold      = false;
        cell.underline = false;
        cell.reverse   = false;
        cell.blink     = false;
    }

    // -----------------------------------------------------------------------
    // Send data back to SSH channel
    // -----------------------------------------------------------------------
    /** Cap on queued responses so a server streaming endless DSR/DA requests can't grow the
     *  queue without bound (reflected write-amplification). Real usage drains after each read. */
    private static final int MAX_PENDING_RESPONSES = 256;

    private void send(String s) {
        if (pendingResponses.size() < MAX_PENDING_RESPONSES)
            pendingResponses.add(s.getBytes(StandardCharsets.US_ASCII));
    }

    /** Drain queued terminal responses and deliver them via the dataListener.
     *  Called by the SSH reader thread AFTER processBytes returns (outside the lock). */
    public void flushResponses() {
        if (dataListener == null) { pendingResponses.clear(); return; }
        byte[] data;
        while ((data = pendingResponses.poll()) != null) {
            dataListener.onData(data);
        }
    }

    private void notifyChange() {
        if (changeListener != null) changeListener.onChange();
    }

    private void notifyAltBufferChanged() {
        if (altBufferListener != null) altBufferListener.onAltBufferChanged(altBufferActive);
    }

    // -----------------------------------------------------------------------
    // Read access for the renderer  (call inside synchronized block)
    // -----------------------------------------------------------------------
    public synchronized int  getRows()            { return rows; }
    public synchronized int  getCols()            { return cols; }
    public synchronized int  getCursorRow()       { return cursorRow; }
    public synchronized int  getCursorCol()       { return cursorCol; }
    public synchronized boolean isCursorVisible() { return cursorVisible; }
    public synchronized boolean isAppCursorKeys() { return appCursorKeys; }
    public synchronized int  getScrollbackSize()  { return scrollback.size(); }
    public synchronized boolean isAltBufferActive() { return altBufferActive; }
    /** True when the program has enabled DECSET 2004 and expects pasted text to arrive wrapped
     *  in ESC[200~ / ESC[201~. */
    public synchronized boolean isBracketedPaste()  { return bracketedPaste; }

    /**
     * Returns the cell at visible row {@code visibleRow}, column {@code col},
     * with {@code scrollOffset} lines scrolled back (0 = showing current buffer bottom).
     */
    public synchronized TerminalCell getCell(int visibleRow, int col, int scrollOffset) {
        int histSize  = scrollback.size();
        int totalRows = histSize + rows;
        int absRow    = visibleRow + (totalRows - rows - scrollOffset);
        return getCellAbs(absRow, col);
    }

    /**
     * Returns the cell at absolute buffer row {@code absRow} (0 = oldest scrollback line,
     * scrollback + live buffer height as the exclusive upper bound — out-of-range rows return
     * null rather than throwing), independent of any viewport/scrollOffset. Used for text
     * selections, whose extent must stay anchored to specific content even as the user scrolls,
     * rather than to whatever happens to be on-screen right now.
     */
    public synchronized TerminalCell getCellAbs(int absRow, int col) {
        int histSize = scrollback.size();
        if (absRow < 0 || absRow >= histSize + rows) return null;
        if (absRow < histSize) {
            TerminalCell[] row = scrollback.get(absRow);
            return col < row.length ? row[col] : null;
        }
        int bufRow = absRow - histSize;
        if (col >= cols) return null;
        return activeBuffer[bufRow][col];
    }

    // -----------------------------------------------------------------------
    // Color resolution
    // -----------------------------------------------------------------------
    public static int resolveColor(int color) {
        if (color == DEFAULT_COLOR) return -1;
        if (color > 255)            return color & 0xFFFFFF;   // inline RGB
        return PALETTE[color & 0xFF];
    }

    // -----------------------------------------------------------------------
    // xterm-256 palette
    // -----------------------------------------------------------------------
    private static int[] buildPalette() {
        int[] p = new int[256];
        p[0]  = rgb(0,0,0);         p[1]  = rgb(128,0,0);     p[2]  = rgb(0,128,0);
        p[3]  = rgb(128,128,0);     p[4]  = rgb(0,0,128);     p[5]  = rgb(128,0,128);
        p[6]  = rgb(0,128,128);     p[7]  = rgb(192,192,192);
        p[8]  = rgb(85,85,85);      p[9]  = rgb(255,85,85);   p[10] = rgb(85,255,85);
        p[11] = rgb(255,255,85);    p[12] = rgb(85,85,255);   p[13] = rgb(255,85,255);
        p[14] = rgb(85,255,255);    p[15] = rgb(255,255,255);
        for (int r = 0; r < 6; r++)
            for (int g = 0; g < 6; g++)
                for (int b = 0; b < 6; b++)
                    p[16+36*r+6*g+b] = rgb(r==0?0:55+40*r, g==0?0:55+40*g, b==0?0:55+40*b);
        for (int i = 0; i < 24; i++) { int v = 8+10*i; p[232+i] = rgb(v,v,v); }
        return p;
    }

    private static int rgb(int r, int g, int b) { return (r << 16) | (g << 8) | b; }

    // -----------------------------------------------------------------------
    // ACS line-drawing map (offsets from 0x60 = '`')
    // -----------------------------------------------------------------------
    //  Offset from 0x60 ('`'):
    //  0=` 1=a 2=b 3=c 4=d 5=e 6=f 7=g 8=h 9=i
    //  10=j 11=k 12=l 13=m 14=n 15=o 16=p 17=q 18=r 19=s
    //  20=t 21=u 22=v 23=w 24=x 25=y 26=z 27={ 28=| 29=} 30=~
    private static final char[] ACS_MAP = {
    //  `     a     b     c     d     e     f     g     h     i
        '◆',  '▒',  '·',  '·',  '·',  '·',  '°',  '±',  '·',  '·',
    //  j     k     l     m     n     o     p     q     r     s
        '┘',  '┐',  '┌',  '└',  '┼',  '─',  '─',  '─',  '─',  '_',
    //  t     u     v     w     x     y     z     {     |     }     ~
        '├',  '┤',  '┴',  '┬',  '│',  '≤',  '≥',  'π',  '≠',  '£',  '·'
    };
}
