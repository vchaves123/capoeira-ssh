package br.com.quatorzebis.ssh.terminal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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

    private static final int[] PALETTE = buildPalette();

    // -----------------------------------------------------------------------
    // Parser states
    // -----------------------------------------------------------------------
    private enum State { NORMAL, ESC, CSI, OSC, OSC_ESC, CHARSET_G0, CHARSET_G1 }

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

    private DataListener      dataListener;
    private ChangeListener    changeListener;
    private AltBufferListener altBufferListener;

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
    }

    // -----------------------------------------------------------------------
    // Listeners
    // -----------------------------------------------------------------------
    public synchronized void setDataListener(DataListener l)         { this.dataListener = l; }
    public synchronized void setChangeListener(ChangeListener l)     { this.changeListener = l; }
    public synchronized void setAltBufferListener(AltBufferListener l) { this.altBufferListener = l; }

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

    private void processC1(int b) {
        switch (b) {
            case 0x84 -> ind();
            case 0x85 -> nel();
            case 0x8D -> ri();
            case 0x9B -> { state = State.CSI; params.clear(); csiPrivate = false; csiIntermediate = false; }
            case 0x9C -> { state = State.NORMAL; oscBuffer.setLength(0); }  // ST — terminates any open string
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
        } else if (state == State.CSI) {
            processCSI(b);
        } else if (state == State.OSC) {
            if      (b == 0x1B) state = State.OSC_ESC;
            else if (b == 0x07) { state = State.NORMAL; oscBuffer.setLength(0); }
            else if (oscBuffer.length() < MAX_OSC_LEN) oscBuffer.append((char) b);
        } else if (state == State.OSC_ESC) {
            if (b == '\\') { state = State.NORMAL; oscBuffer.setLength(0); }
            else           { state = State.OSC; if (oscBuffer.length() < MAX_OSC_LEN) oscBuffer.append((char) b); }
        } else if (state == State.CHARSET_G0) {
            g0LineDrawing = (b == '0');
            state = State.NORMAL;
        } else if (state == State.CHARSET_G1) {
            g1LineDrawing = (b == '0');
            state = State.NORMAL;
        }
    }

    private void processCodePoint(int cp) {
        char ch = cp < 0x10000 ? (char) cp : '?';
        // ACS line-drawing translation
        if ((useG1 ? g1LineDrawing : g0LineDrawing) && cp >= 0x60 && cp <= 0x7E)
            ch = ACS_MAP[cp - 0x60];

        if (wrapPending) {
            wrapPending = false;
            cursorCol   = 0;
            advanceLine();
        }

        writeCell(cursorRow, cursorCol, ch);

        if (cursorCol < cols - 1) cursorCol++;
        else wrapPending = true;
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
            params.set(idx, params.get(idx) * 10 + (b - '0'));
            return;
        }
        if (b == ';') { params.add(0); return; }

        state = State.NORMAL;

        // Sequences with intermediate bytes (e.g. DECSCUSR \e[N SP q) — just ignore
        if (csiIntermediate) { csiIntermediate = false; return; }

        if (csiPrivate) { processCSIPrivate(b); return; }

        int p1 = params.isEmpty()       ? 0 : params.get(0);
        int p2 = params.size() < 2      ? 0 : params.get(1);

        switch ((char) b) {
            case 'A' -> moveCursor(cursorRow - Math.max(1, p1), cursorCol);
            case 'B' -> moveCursor(cursorRow + Math.max(1, p1), cursorCol);
            case 'C' -> moveCursor(cursorRow, cursorCol + Math.max(1, p1));
            case 'D' -> moveCursor(cursorRow, cursorCol - Math.max(1, p1));
            case 'E' -> moveCursor(cursorRow + Math.max(1, p1), 0);
            case 'F' -> moveCursor(cursorRow - Math.max(1, p1), 0);
            case 'G' -> moveCursor(cursorRow, Math.max(1, p1) - 1);
            case 'H', 'f' -> moveCursor(Math.max(1, p1) - 1, Math.max(1, p2) - 1);
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
                case 12          -> {} // cursor blink on  — ignored
                case 25          -> cursorVisible = true;
                case 47, 1047    -> activateAltBuffer(true);
                case 1049        -> activateAltBuffer(true);
                // commonly sent by vim/bash — safe to ignore
                case 2004        -> {} // bracketed paste enable
                case 1000, 1002,
                     1003, 1006  -> {} // mouse tracking
                case 1004        -> {} // focus events
            }
        } else if (b == 'l') {
            switch (p1) {
                case 1           -> appCursorKeys = false;
                case 3           -> {} // 80-column mode — ignored
                case 4           -> {} // smooth scroll — ignored
                case 12          -> {} // cursor blink off — ignored
                case 25          -> cursorVisible = false;
                case 47, 1047    -> deactivateAltBuffer(true);
                case 1049        -> deactivateAltBuffer(true);
                case 2004        -> {} // bracketed paste disable
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

    private void decstbm() {
        int top = params.isEmpty()       ? 1     : params.get(0);
        int bot = params.size() < 2      ? rows  : params.get(1);
        scrollTop    = Math.max(0, top - 1);
        scrollBottom = Math.min(rows - 1, bot - 1);
        if (scrollTop >= scrollBottom) { scrollTop = 0; scrollBottom = rows - 1; }
        cursorRow   = 0; cursorCol = 0; wrapPending = false;
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
    private void writeCell(int row, int col, char ch) {
        if (row < 0 || row >= rows || col < 0 || col >= cols) return;
        TerminalCell cell = activeBuffer[row][col];
        cell.character = ch;
        cell.fgColor   = currentAttrs.fgColor;
        cell.bgColor   = currentAttrs.bgColor;
        cell.bold      = currentAttrs.bold;
        cell.underline = currentAttrs.underline;
        cell.reverse   = currentAttrs.reverse;
        cell.blink     = currentAttrs.blink;
    }

    private void clearLineRange(int row, int colStart, int colEnd) {
        if (row < 0 || row >= rows) return;
        for (int c = colStart; c < colEnd && c < cols; c++)
            eraseCell(activeBuffer[row][c]);
    }

    /**
     * Erases a single cell using the current SGR background colour.
     * VT100 spec: erase operations fill with space + current bg, not DEFAULT_COLOR.
     * This is what makes ncurses/YaST coloured backgrounds render correctly.
     *
     * Special case for alternate buffer (ncurses TUI apps like YaST/vim):
     * when the current SGR background is DEFAULT_COLOR (i.e. after \e[0m reset),
     * preserve the cell's existing explicit background rather than wiping it to
     * DEFAULT_COLOR.  ncurses relies on background-color-erase (bce) but on
     * navigation it issues \e[0m + \e[K for rows that visually should keep their
     * coloured background, causing "black stripes" in gap areas.  Preserving the
     * existing colour avoids this without breaking intentional explicit-colour erases.
     */
    private void eraseCell(TerminalCell cell) {
        cell.character = ' ';
        cell.fgColor   = DEFAULT_COLOR;
        if (currentAttrs.bgColor != DEFAULT_COLOR) {
            cell.bgColor = currentAttrs.bgColor;
        } else if (altBufferActive && cell.bgColor != DEFAULT_COLOR) {
            // preserve the cell's existing coloured background in TUI apps
        } else {
            cell.bgColor = DEFAULT_COLOR;
        }
        cell.bold      = false;
        cell.underline = false;
        cell.reverse   = false;
        cell.blink     = false;
    }

    // -----------------------------------------------------------------------
    // Send data back to SSH channel
    // -----------------------------------------------------------------------
    private void send(String s) {
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

    /**
     * Returns the cell at visible row {@code visibleRow}, column {@code col},
     * with {@code scrollOffset} lines scrolled back (0 = showing current buffer bottom).
     */
    public synchronized TerminalCell getCell(int visibleRow, int col, int scrollOffset) {
        int histSize     = scrollback.size();
        int totalRows    = histSize + rows;
        int absRow       = visibleRow + (totalRows - rows - scrollOffset);
        if (absRow < histSize) {
            TerminalCell[] row = scrollback.get(absRow);
            return col < row.length ? row[col] : null;
        }
        int bufRow = absRow - histSize;
        if (bufRow < 0 || bufRow >= rows || col >= cols) return null;
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
