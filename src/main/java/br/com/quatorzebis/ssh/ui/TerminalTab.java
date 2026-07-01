package br.com.quatorzebis.ssh.ui;

import br.com.quatorzebis.ssh.model.SessionInfo;
import br.com.quatorzebis.ssh.ssh.SshConnection;
import br.com.quatorzebis.ssh.terminal.TerminalCell;
import br.com.quatorzebis.ssh.terminal.TerminalEmulator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * A single terminal tab backed by an SSH connection.
 * Renders an {@link TerminalEmulator} onto an SWT {@link Canvas} with off-screen
 * double-buffering to avoid flickering.
 */
public class TerminalTab {

    // -----------------------------------------------------------------------
    // Widgets
    // -----------------------------------------------------------------------
    private CTabItem tabItem;
    private final Canvas   canvas;
    private final Display  display;

    // -----------------------------------------------------------------------
    // Backend
    // -----------------------------------------------------------------------
    private final TerminalEmulator emulator;
    private final SshConnection    connection;

    // -----------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------
    private Font  termFont;
    private int   charWidth;
    private int   charHeight;

    private Color defaultBg;
    private Color defaultFg;
    private int   termFontSize = 14;

    private Image offscreenBuffer;

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------
    private volatile boolean closed        = false;
    private volatile boolean disconnected  = false;
    private boolean          cursorBlink   = false;
    private int              scrollOffset  = 0;    // 0 = pinned to bottom

    private final SessionInfo sessionInfo;
    private final String      tabTitle;
    private Image             redDotIcon;
    private Image             blueDarkIcon;
    private Image             blueLightIcon;

    /** True while this tab has unread activity (not the active tab). */
    private volatile boolean  activityPending  = false;
    /** Timestamp (ms) of the last real terminal data chunk. */
    private volatile long     lastActivityTime = 0;
    private boolean           blinkRunning     = false;
    private boolean           blinkPhase       = false;

    /** Called (on the UI thread) when the user requests a reconnect. */
    private Runnable onReconnectRequest;
    /** Called (on the UI thread) whenever the connection state changes (connect / disconnect). */
    private Runnable onStateChanged;

    /** Display-level filter that intercepts Alt+key before SWT menu mnemonics. */
    private Listener altFilter;
    /** Display-level filter that intercepts F1-F12 before SWT/OS default actions. */
    private Listener fKeyFilter;

    // ── Text selection state ─────────────────────────────────────────────────
    /** Selection anchor (col, row) in terminal cell coordinates; -1 = no selection. */
    private int selAnchorCol = -1, selAnchorRow = -1;
    /** Selection end — updated as the mouse moves. */
    private int selEndCol    = -1, selEndRow    = -1;
    /** True while the trailing MouseUp of a double-click should be ignored. */
    private boolean suppressNextMouseUp = false;

    /** Pending debounced resize runnable; cancelled and rescheduled on every SWT.Resize event. */
    private Runnable pendingResize;

    // -----------------------------------------------------------------------
    // Logging
    // -----------------------------------------------------------------------
    private OutputStream logStream;
    private static final DateTimeFormatter LOG_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final java.util.concurrent.atomic.AtomicInteger LOG_SEQ = new java.util.concurrent.atomic.AtomicInteger();

    // State machine for stripping ANSI escape sequences from the log stream.
    private enum AnsiState { NORMAL, ESC, ESC_INTERMEDIATE, CSI, OSC, OSC_ESC }
    private AnsiState ansiState = AnsiState.NORMAL;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------
    public TerminalTab(CTabFolder folder, SessionInfo info, String password) {
        this.display     = folder.getDisplay();
        this.sessionInfo = info;
        this.tabTitle    = info.label();

        tabItem = new CTabItem(folder, SWT.CLOSE);
        tabItem.setText(tabTitle);

        canvas = new Canvas(folder, SWT.NO_BACKGROUND | SWT.V_SCROLL);
        tabItem.setControl(canvas);

        defaultBg = new Color(display, 0,   0,   0  );
        defaultFg = new Color(display, 204, 204, 204);

        initFont();

        emulator = new TerminalEmulator(80, 24);
        emulator.setChangeListener(() ->
            display.asyncExec(() -> {
                if (canvas.isDisposed()) return;
                if (hasSelection()) clearSelection();
                canvas.redraw();
            })
        );

        connection = new SshConnection();

        // Deliver queued terminal responses (DSR, DA, XTWINOPS) to SSH.
        // flushResponses() is called from readSsh() after each processBytes(),
        // outside the synchronized lock, so this callback never blocks SSH reads.
        emulator.setDataListener(data -> {
            if (connection.isConnected()) {
                try { connection.send(data); } catch (IOException ignored) {}
            }
        });

        // Reset scroll offset when entering/leaving alternate screen (e.g. vim, yast)
        emulator.setAltBufferListener(active ->
            display.asyncExec(() -> {
                if (canvas.isDisposed()) return;
                scrollOffset = 0;
                updateScrollBar();
                if (connection.isConnected()) {
                    int c = emulator.getCols(), r = emulator.getRows();
                    connection.updatePtySize(c, r, c * charWidth, r * charHeight);
                }
                canvas.redraw();
            })
        );

        setupCanvas();
        setupScrollBar();
        startCursorBlink();

        startSshThread(info, password);
    }

    // -----------------------------------------------------------------------
    // Font
    // -----------------------------------------------------------------------
    private void initFont() {
        String fontName = display.getFontList("Consolas", true).length > 0
                          ? "Consolas" : "Courier New";
        if (termFont != null && !termFont.isDisposed()) termFont.dispose();
        termFont = new Font(display, fontName, termFontSize, SWT.NORMAL);

        GC gc = new GC(display);
        gc.setFont(termFont);
        Point sz = gc.stringExtent("W");
        charWidth  = sz.x;
        charHeight = gc.getFontMetrics().getHeight();
        gc.dispose();
    }

    // -----------------------------------------------------------------------
    // Canvas setup
    // -----------------------------------------------------------------------
    private void setupCanvas() {
        canvas.addPaintListener(e -> render(e.gc));
        // Debounce resize: SWT.Resize fires on every pixel during drag.
        // We cancel+reschedule a timer so updateTerminalSize() only runs
        // 80 ms after the user *stops* resizing, preventing lock contention
        // with the SSH reader thread and UI stutter.
        canvas.addListener(SWT.Resize, e -> {
            disposeOffscreen();   // free old buffer immediately to release memory
            if (pendingResize != null) display.timerExec(-1, pendingResize);
            pendingResize = () -> {
                pendingResize = null;
                if (!canvas.isDisposed()) updateTerminalSize();
            };
            display.timerExec(80, pendingResize);
        });
        canvas.addTraverseListener(e -> e.doit = false);

        canvas.addKeyListener(new org.eclipse.swt.events.KeyAdapter() {
            @Override
            public void keyPressed(org.eclipse.swt.events.KeyEvent e) {
                handleKey(e);
            }
        });

        canvas.addListener(SWT.MouseWheel, e -> { if (!disconnected) scroll(e.count > 0 ? -3 : 3); });

        canvas.addListener(SWT.MouseDown, e -> {
            if (disconnected) { triggerReconnect(); return; }
            canvas.setFocus();
            if (e.button == 1) {
                // Start selection
                selAnchorCol = e.x / charWidth;
                selAnchorRow = e.y / charHeight;
                selEndCol    = selAnchorCol;
                selEndRow    = selAnchorRow;
                canvas.redraw();
            } else if (e.button == 3) {
                pasteFromClipboard();
            }
        });

        canvas.addListener(SWT.MouseMove, e -> {
            if (selAnchorCol < 0 || (e.stateMask & SWT.BUTTON1) == 0) return;
            selEndCol = Math.max(0, Math.min(e.x / charWidth,  emulator.getCols() - 1));
            selEndRow = Math.max(0, Math.min(e.y / charHeight, visibleRows() - 1));
            canvas.redraw();
        });

        canvas.addListener(SWT.MouseUp, e -> {
            if (e.button != 1 || selAnchorCol < 0) return;
            if (suppressNextMouseUp) { suppressNextMouseUp = false; return; }
            selEndCol = Math.max(0, Math.min(e.x / charWidth,  emulator.getCols() - 1));
            selEndRow = Math.max(0, Math.min(e.y / charHeight, visibleRows() - 1));
            String text = getSelectedText();
            if (text != null && !text.isEmpty()) copyToClipboard(text);
            else clearSelection();
            canvas.redraw();
        });

        canvas.addListener(SWT.MouseDoubleClick, e -> {
            if (e.button != 1 || disconnected) return;
            int col = e.x / charWidth;
            int row = e.y / charHeight;
            int cols = emulator.getCols();
            int rows = visibleRows();
            if (col < 0 || col >= cols || row < 0 || row >= rows) return;
            // expand left
            int startCol = col;
            while (startCol > 0 && isWordChar(emulator.getCell(row, startCol - 1, scrollOffset))) startCol--;
            // expand right
            int endCol = col;
            while (endCol < cols - 1 && isWordChar(emulator.getCell(row, endCol + 1, scrollOffset))) endCol++;
            selAnchorCol = startCol; selAnchorRow = row;
            selEndCol    = endCol;   selEndRow    = row;
            suppressNextMouseUp = true;
            canvas.redraw();
            String text = getSelectedText();
            if (text != null && !text.isEmpty()) copyToClipboard(text);
        });
        canvas.setBackground(defaultBg);

        // Alt+key must be captured at Display level; otherwise SWT activates
        // menu mnemonics (e.g. Alt+S → "Session" menu) before the canvas sees them.
        altFilter = e -> {
            if (canvas.isDisposed() || !canvas.isFocusControl()) return;
            if ((e.stateMask & SWT.ALT) == 0) return;
            if (scrollOffset != 0) { scrollOffset = 0; updateScrollBar(); }
            byte[] seq = mapAltKey(e);
            if (seq != null) {
                e.doit = false;   // prevent SWT menu activation
                if (connection.isConnected()) {
                    try { connection.send(seq); } catch (IOException ignored) {}
                }
            }
        };
        display.addFilter(SWT.KeyDown, altFilter);

        // F1-F12 must be captured before the OS/SWT acts on them (e.g. F10 → menu bar).
        fKeyFilter = e -> {
            if (canvas.isDisposed() || !canvas.isFocusControl()) return;
            if ((e.stateMask & SWT.ALT) != 0) return; // handled by altFilter
            byte[] seq = mapFKey(e.keyCode);
            if (seq != null) {
                e.doit = false;
                if (!disconnected && connection.isConnected()) {
                    try { connection.send(seq); } catch (IOException ignored) {}
                }
            }
        };
        display.addFilter(SWT.KeyDown, fKeyFilter);
    }

    // -----------------------------------------------------------------------
    // Scroll bar
    // -----------------------------------------------------------------------
    private void setupScrollBar() {
        ScrollBar sb = canvas.getVerticalBar();
        if (sb == null) return;
        sb.setMinimum(0); sb.setMaximum(1); sb.setSelection(0);
        sb.addListener(SWT.Selection, e -> {
            int max = sb.getMaximum() - sb.getThumb();
            scrollOffset = max - sb.getSelection();
            canvas.redraw();
        });
    }

    private void updateScrollBar() {
        ScrollBar sb = canvas.getVerticalBar();
        if (sb == null || sb.isDisposed()) return;
        int hist = emulator.getScrollbackSize();
        int rows = emulator.getRows();
        // In alternate buffer (vim, yast) or no scrollback: keep scrollbar present
        // but collapsed (thumb = max) so it stays disabled without changing canvas width
        if (emulator.isAltBufferActive() || hist == 0) {
            sb.setMinimum(0);
            sb.setMaximum(1);
            sb.setThumb(1);
            sb.setSelection(0);
            return;
        }
        int total = hist + rows;
        sb.setMinimum(0);
        sb.setMaximum(total);
        sb.setThumb(rows);
        sb.setPageIncrement(rows);
        sb.setIncrement(1);
        sb.setSelection(Math.max(0, total - rows - scrollOffset));
    }

    private void scroll(int delta) {
        scrollOffset = Math.max(0, Math.min(emulator.getScrollbackSize(), scrollOffset - delta));
        updateScrollBar();
        canvas.redraw();
    }

    // -----------------------------------------------------------------------
    // Terminal size
    // -----------------------------------------------------------------------
    private void updateTerminalSize() {
        if (canvas.isDisposed() || charWidth == 0 || charHeight == 0) return;
        Rectangle r = canvas.getClientArea();
        int newCols = Math.max(1, r.width  / charWidth);
        int newRows = Math.max(1, r.height / charHeight);
        emulator.resize(newCols, newRows);
        if (connection.isConnected())
            connection.updatePtySize(newCols, newRows, r.width, r.height);
    }

    // -----------------------------------------------------------------------
    // Rendering  (double-buffered via off-screen Image)
    // -----------------------------------------------------------------------
    private void render(GC screen) {
        Rectangle area = canvas.getClientArea();
        if (area.width <= 0 || area.height <= 0) return;

        if (offscreenBuffer == null
                || offscreenBuffer.getBounds().width  != area.width
                || offscreenBuffer.getBounds().height != area.height) {
            disposeOffscreen();
            offscreenBuffer = new Image(display, area.width, area.height);
        }

        GC gc = new GC(offscreenBuffer);
        try {
            gc.setFont(termFont);
            gc.setBackground(defaultBg);
            gc.fillRectangle(area);

            int cols    = emulator.getCols();
            int rows    = emulator.getRows();
            int curRow  = emulator.getCursorRow();
            int curCol  = emulator.getCursorCol();

            int rightMargin = cols * charWidth;
            int bottomMargin = rows * charHeight;

            for (int r = 0; r < rows; r++) {
                int rowBg = -1; // last non-cursor bg for right-margin fill
                for (int c = 0; c < cols; c++) {
                    TerminalCell cell = emulator.getCell(r, c, scrollOffset);
                    if (cell == null) continue;

                    int fg = TerminalEmulator.resolveColor(cell.fgColor);
                    int bg = TerminalEmulator.resolveColor(cell.bgColor);

                    boolean rev = cell.reverse;
                    if (rev) { int tmp = fg; fg = bg; bg = tmp; }

                    // Cursor highlight (only when scrolled to bottom)
                    boolean isCursor = scrollOffset == 0
                                    && r == curRow && c == curCol
                                    && emulator.isCursorVisible();
                    if (isCursor && cursorBlink) {
                        int tmp = (fg < 0 ? 0xCCCCCC : fg);
                        fg = (bg < 0 ? 0 : bg);
                        bg = tmp;
                        if (bg < 0) bg = 0xFFFFFF;
                    }

                    int px = c * charWidth;
                    int py = r * charHeight;

                    if (bg >= 0) {
                        Color cbg = swtRgb(bg);
                        gc.setBackground(cbg);
                        gc.fillRectangle(px, py, charWidth, charHeight);
                        cbg.dispose();
                        if (!isCursor) rowBg = bg; // don't leak cursor highlight into right margin
                    }

                    if (cell.character != ' ' && cell.character != '\0') {
                        Color cfg = fg >= 0 ? swtRgb(fg) : null;
                        gc.setForeground(cfg != null ? cfg : defaultFg);

                        if (cell.bold) {
                            Font boldFont = new Font(display, termFont.getFontData()[0].getName(), termFontSize, SWT.BOLD);
                            gc.setFont(boldFont);
                            gc.drawString(String.valueOf(cell.character), px, py, true);
                            gc.setFont(termFont);
                            boldFont.dispose();
                        } else {
                            gc.drawString(String.valueOf(cell.character), px, py, true);
                        }

                        if (cell.underline) {
                            gc.drawLine(px, py + charHeight - 1, px + charWidth - 1, py + charHeight - 1);
                        }

                        if (cfg != null) cfg.dispose();
                    }
                }
                // Extend last cell's background into the fractional-column gap at the right.
                if (rightMargin < area.width && rowBg >= 0) {
                    Color cbg = swtRgb(rowBg);
                    gc.setBackground(cbg);
                    gc.fillRectangle(rightMargin, r * charHeight, area.width - rightMargin, charHeight);
                    cbg.dispose();
                }
            }
            // Fill fractional row gap at the bottom (already covered by initial fill, but be explicit)
            if (bottomMargin < area.height) {
                gc.setBackground(defaultBg);
                gc.fillRectangle(0, bottomMargin, area.width, area.height - bottomMargin);
            }
            // ── Selection highlight overlay ──────────────────────────────
            if (selAnchorCol >= 0 && hasSelection()) {
                int[] norm = normalizedSelection();
                int r0 = norm[0], c0 = norm[1], r1 = norm[2], c1 = norm[3];
                gc.setAlpha(80);
                gc.setBackground(new Color(display, 100, 160, 255));
                for (int sr = r0; sr <= r1; sr++) {
                    int sc = (sr == r0) ? c0 : 0;
                    int ec = (sr == r1) ? c1 : cols - 1;
                    gc.fillRectangle(sc * charWidth, sr * charHeight,
                                     (ec - sc + 1) * charWidth, charHeight);
                }
                gc.setAlpha(255);
            }

            if (disconnected) {
                // Semi-transparent gray overlay
                gc.setAlpha(180);
                gc.setBackground(new Color(display, 20, 20, 20));
                gc.fillRectangle(area);
                gc.setAlpha(255);

                Font overlayFont = new Font(display, termFont.getFontData()[0].getName(), 16, SWT.BOLD);
                gc.setFont(overlayFont);
                gc.setForeground(new Color(display, 200, 200, 200));

                String line1 = "Connection closed";
                Point e1 = gc.stringExtent(line1);
                gc.drawString(line1, (area.width - e1.x) / 2, area.height / 2 - e1.y - 6, true);

                gc.setFont(termFont);
                gc.setForeground(new Color(display, 100, 180, 255));
                String line2 = "▶  Click to reconnect";
                Point e2 = gc.stringExtent(line2);
                gc.drawString(line2, (area.width - e2.x) / 2, area.height / 2 + 6, true);

                overlayFont.dispose();
            }
        } finally {
            gc.dispose();
        }

        screen.drawImage(offscreenBuffer, 0, 0);
        updateScrollBar();
    }

    private Color swtRgb(int rgb) {
        return new Color(display, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    // -----------------------------------------------------------------------
    // Cursor blink
    // -----------------------------------------------------------------------
    private void startCursorBlink() {
        Runnable blink = new Runnable() {
            @Override public void run() {
                if (closed || canvas.isDisposed()) return;
                cursorBlink = !cursorBlink;
                canvas.redraw();
                display.timerExec(500, this);
            }
        };
        display.timerExec(500, blink);
    }

    // -----------------------------------------------------------------------
    // Keyboard
    // -----------------------------------------------------------------------
    private void handleKey(org.eclipse.swt.events.KeyEvent e) {
        // Alt+key is handled entirely by altFilter — skip here to avoid double-send
        if ((e.stateMask & SWT.ALT) != 0) return;

        if (scrollOffset != 0) { scrollOffset = 0; updateScrollBar(); }
        if (hasSelection()) { clearSelection(); canvas.redraw(); }

        byte[] seq = mapKey(e);
        if (seq != null && connection.isConnected()) {
            try { connection.send(seq); } catch (IOException ignored) {}
        }
    }

    private byte[] mapKey(org.eclipse.swt.events.KeyEvent e) {
        boolean app = emulator.isAppCursorKeys();
        int     key = e.keyCode;
        char    ch  = e.character;

        if (key == SWT.ARROW_UP)    return bytes(app ? "\033OA" : "\033[A");
        if (key == SWT.ARROW_DOWN)  return bytes(app ? "\033OB" : "\033[B");
        if (key == SWT.ARROW_RIGHT) return bytes(app ? "\033OC" : "\033[C");
        if (key == SWT.ARROW_LEFT)  return bytes(app ? "\033OD" : "\033[D");

        // F1-F12 are intercepted at the display filter level (fKeyFilter) to prevent
        // OS/SWT defaults (e.g. F10 → menu). They never reach this handler.

        if (key == SWT.HOME)      return bytes("\033[H");
        if (key == SWT.END)       return bytes("\033[F");
        if (key == SWT.PAGE_UP)   return bytes("\033[5~");
        if (key == SWT.PAGE_DOWN) return bytes("\033[6~");
        if (key == SWT.INSERT)    return bytes("\033[2~");
        if (key == SWT.DEL)       return bytes("\033[3~");

        if (ch == SWT.BS || key == SWT.BS) return new byte[]{ (byte) 0x7F };
        if (ch == '\r')  return new byte[]{ '\r' };
        // Shift+Tab → Back-Tab (ESC[Z); must be checked before plain Tab
        if (key == SWT.TAB && (e.stateMask & SWT.SHIFT) != 0) return bytes("\033[Z");
        if (ch == '\t')  return new byte[]{ '\t' };
        if (ch == 0x1B)  return new byte[]{ 0x1B };

        if ((e.stateMask & SWT.CTRL) != 0 && ch >= 1 && ch <= 26)
            return new byte[]{ (byte) ch };

        if (ch >= 0x20 && ch != 0xFFFF)
            return String.valueOf(ch).getBytes(StandardCharsets.UTF_8);

        return null;
    }

    /**
     * Maps an Alt+key event to its terminal escape sequence.
     * Alt+letter  → ESC + letter  (meta key, used by ncurses / YaST menus)
     * Alt+special → ESC + inner sequence
     */
    private byte[] mapAltKey(Event e) {
        int     key   = e.keyCode;
        boolean shift = (e.stateMask & SWT.SHIFT) != 0;
        boolean app   = emulator.isAppCursorKeys();

        // Alt + arrows
        if (key == SWT.ARROW_UP)    return altEsc(app ? "\033OA" : "\033[A");
        if (key == SWT.ARROW_DOWN)  return altEsc(app ? "\033OB" : "\033[B");
        if (key == SWT.ARROW_RIGHT) return altEsc(app ? "\033OC" : "\033[C");
        if (key == SWT.ARROW_LEFT)  return altEsc(app ? "\033OD" : "\033[D");

        // Alt + letter → ESC + letter  (SWT keyCode for letters is always lowercase)
        if (key >= 'a' && key <= 'z') {
            char c = shift ? (char)(key - ('a' - 'A')) : (char) key;
            return new byte[]{ 0x1B, (byte) c };
        }

        // Alt + digit → ESC + digit
        if (key >= '0' && key <= '9') return new byte[]{ 0x1B, (byte) key };

        // Alt + function keys
        if (key == SWT.F1)  return altEsc("\033OP");
        if (key == SWT.F2)  return altEsc("\033OQ");
        if (key == SWT.F3)  return altEsc("\033OR");
        if (key == SWT.F4)  return altEsc("\033OS");
        if (key == SWT.F5)  return altEsc("\033[15~");
        if (key == SWT.F6)  return altEsc("\033[17~");
        if (key == SWT.F7)  return altEsc("\033[18~");
        if (key == SWT.F8)  return altEsc("\033[19~");
        if (key == SWT.F9)  return altEsc("\033[20~");
        if (key == SWT.F10) return altEsc("\033[21~");
        if (key == SWT.F11) return altEsc("\033[23~");
        if (key == SWT.F12) return altEsc("\033[24~");

        // Alt + navigation
        if (key == SWT.HOME)      return altEsc("\033[H");
        if (key == SWT.END)       return altEsc("\033[F");
        if (key == SWT.PAGE_UP)   return altEsc("\033[5~");
        if (key == SWT.PAGE_DOWN) return altEsc("\033[6~");
        if (key == SWT.DEL)       return altEsc("\033[3~");

        // Alt + other printable character (punctuation, etc.)
        char ch = e.character;
        if (ch != 0 && ch >= 0x20 && ch != 0xFFFF) {
            byte[] cb = String.valueOf(ch).getBytes(StandardCharsets.UTF_8);
            byte[] r  = new byte[1 + cb.length];
            r[0] = 0x1B;
            System.arraycopy(cb, 0, r, 1, cb.length);
            return r;
        }
        return null;
    }

    /** Prepends ESC (0x1B) to an already-encoded sequence string. */
    private static byte[] altEsc(String inner) {
        byte[] ib = inner.getBytes(StandardCharsets.US_ASCII);
        byte[] r  = new byte[1 + ib.length];
        r[0] = 0x1B;
        System.arraycopy(ib, 0, r, 1, ib.length);
        return r;
    }

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    private static byte[] mapFKey(int key) {
        if (key == SWT.F1)  return bytes("\033OP");
        if (key == SWT.F2)  return bytes("\033OQ");
        if (key == SWT.F3)  return bytes("\033OR");
        if (key == SWT.F4)  return bytes("\033OS");
        if (key == SWT.F5)  return bytes("\033[15~");
        if (key == SWT.F6)  return bytes("\033[17~");
        if (key == SWT.F7)  return bytes("\033[18~");
        if (key == SWT.F8)  return bytes("\033[19~");
        if (key == SWT.F9)  return bytes("\033[20~");
        if (key == SWT.F10) return bytes("\033[21~");
        if (key == SWT.F11) return bytes("\033[23~");
        if (key == SWT.F12) return bytes("\033[24~");
        return null;
    }

    // -----------------------------------------------------------------------
    // SSH connection lifecycle
    // -----------------------------------------------------------------------
    private void startSshThread(SessionInfo info, String password) {
        Thread t = new Thread(() -> runSsh(info, password), "ssh-" + info.label());
        t.setDaemon(true);
        t.start();
    }

    private void openLogFile(SessionInfo info) {
        if (!info.logEnabled) return;
        try {
            String dir = (info.logDir != null && !info.logDir.isBlank())
                         ? info.logDir
                         : System.getProperty("user.home") + "/.14bis/screen_captures";
            Path logDir = Path.of(dir);
            Files.createDirectories(logDir);
            String ts       = LocalDateTime.now().format(LOG_TS);
            String baseName = (info.logFileName != null && !info.logFileName.isBlank())
                              ? info.logFileName.replaceAll("[^\\w\\-.]", "_")
                              : info.host.replaceAll("[^\\w\\-.]", "_");
            String candidate = ts + "_" + baseName;
            Path   file = logDir.resolve(candidate + ".log");
            if (Files.exists(file))
                file = logDir.resolve(candidate + "_" + LOG_SEQ.incrementAndGet() + ".log");
            logStream  = Files.newOutputStream(file,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            ansiState  = AnsiState.NORMAL;
        } catch (IOException e) {
            logStream = null;
        }
    }

    private void writeLog(byte[] buf, int len) {
        if (logStream == null) return;
        byte[] filtered = stripAnsi(buf, len);
        if (filtered.length == 0) return;
        try { logStream.write(filtered); logStream.flush(); }
        catch (IOException ignored) { logStream = null; }
    }

    private byte[] stripAnsi(byte[] buf, int len) {
        byte[] out = new byte[len];
        int    w   = 0;
        for (int i = 0; i < len; i++) {
            int b = buf[i] & 0xFF;
            switch (ansiState) {
                case NORMAL:
                    if (b == 0x1B) {
                        ansiState = AnsiState.ESC;
                    } else if (b == '\r' || b == '\n' || b == '\t') {
                        out[w++] = (byte) b;
                    } else if (b >= 0x20 && b < 0x7F) {
                        out[w++] = (byte) b;
                    } else if (b >= 0x80) {
                        // UTF-8 continuation / leading bytes — pass through
                        out[w++] = (byte) b;
                    }
                    // other control chars (0x00-0x1F except \r\n\t) — discard
                    break;
                case ESC:
                    if (b == '[') {
                        ansiState = AnsiState.CSI;
                    } else if (b == ']') {
                        ansiState = AnsiState.OSC;
                    } else if (b >= 0x20 && b <= 0x2F) {
                        // Intermediate byte (e.g. ESC # B, ESC ( B) — consume one more byte
                        ansiState = AnsiState.ESC_INTERMEDIATE;
                    } else {
                        // 2-char ESC sequence — consume this byte and return to NORMAL
                        ansiState = AnsiState.NORMAL;
                    }
                    break;
                case ESC_INTERMEDIATE:
                    // Final byte of a 3-char ESC sequence — discard and return to NORMAL
                    ansiState = AnsiState.NORMAL;
                    break;
                case CSI:
                    // CSI ends at a byte in 0x40–0x7E (the "final" byte)
                    if (b >= 0x40 && b <= 0x7E) ansiState = AnsiState.NORMAL;
                    break;
                case OSC:
                    if (b == 0x07) {            // BEL terminates OSC
                        ansiState = AnsiState.NORMAL;
                    } else if (b == 0x1B) {     // ESC inside OSC → expect '\'
                        ansiState = AnsiState.OSC_ESC;
                    }
                    break;
                case OSC_ESC:
                    // ESC \ (ST) terminates OSC
                    ansiState = AnsiState.NORMAL;
                    break;
            }
        }
        return java.util.Arrays.copyOf(out, w);
    }

    private void closeLog() {
        if (logStream == null) return;
        try { logStream.close(); } catch (IOException ignored) {}
        logStream = null;
    }

    private void runSsh(SessionInfo info, String password) {
        try {
            connection.connect(info, password);
            openLogFile(info);
            display.asyncExec(() -> {
                if (!canvas.isDisposed()) { updateTerminalSize(); canvas.setFocus(); }
            });
            readSsh();
        } catch (Exception ex) {
            if (closed) return;
            String msg = ex.getMessage();
            display.asyncExec(() -> {
                if (canvas.isDisposed()) return;
                MessageBox mb = new MessageBox(canvas.getShell(), SWT.ICON_ERROR | SWT.OK);
                mb.setText("Connection error");
                mb.setMessage("Could not connect to " + info.label() + ":\n" + msg);
                mb.open();
            });
            handleDisconnect();
        }
    }

    private void readSsh() {
        try {
            InputStream in  = connection.getInputStream();
            byte[]      buf = new byte[4096];
            int         n;
            while (!closed && (n = in.read(buf)) != -1) {
                writeLog(buf, n);
                emulator.processBytes(buf, 0, n);
                emulator.flushResponses();
                if (n > 3) notifyActivity();
            }
        } catch (IOException ignored) {
        } finally {
            closeLog();
            connection.close();
            if (!closed) handleDisconnect();
        }
    }

    // -----------------------------------------------------------------------
    // Activity notification (blue blinking dot on background tab)
    // -----------------------------------------------------------------------
    private static final int  BLINK_INTERVAL_MS = 500;
    private static final long IDLE_THRESHOLD_MS = 1500; // ms without data → stop blinking

    // -----------------------------------------------------------------------
    // Copy / Paste
    // -----------------------------------------------------------------------
    private int visibleRows() {
        Rectangle r = canvas.getClientArea();
        return charHeight > 0 ? r.height / charHeight : emulator.getRows();
    }

    private boolean hasSelection() {
        return selAnchorCol >= 0
            && (selAnchorRow != selEndRow || selAnchorCol != selEndCol);
    }

    /** Returns [r0, c0, r1, c1] with start ≤ end in reading order. */
    private int[] normalizedSelection() {
        int r0 = selAnchorRow, c0 = selAnchorCol;
        int r1 = selEndRow,    c1 = selEndCol;
        if (r0 > r1 || (r0 == r1 && c0 > c1)) {
            int tr = r0; r0 = r1; r1 = tr;
            int tc = c0; c0 = c1; c1 = tc;
        }
        return new int[]{ r0, c0, r1, c1 };
    }

    private String getSelectedText() {
        if (!hasSelection()) return null;
        int[] n = normalizedSelection();
        int r0 = n[0], c0 = n[1], r1 = n[2], c1 = n[3];
        StringBuilder sb = new StringBuilder();
        int cols = emulator.getCols();
        for (int r = r0; r <= r1; r++) {
            int sc = (r == r0) ? c0 : 0;
            int ec = (r == r1) ? c1 : cols - 1;
            StringBuilder line = new StringBuilder();
            for (int c = sc; c <= ec; c++) {
                TerminalCell cell = emulator.getCell(r, c, scrollOffset);
                line.append(cell != null && cell.character != '\0' ? cell.character : ' ');
            }
            // Strip trailing spaces from each line (except last segment)
            if (r < r1) {
                int end = line.length();
                while (end > 0 && line.charAt(end - 1) == ' ') end--;
                sb.append(line, 0, end).append('\n');
            } else {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private void clearSelection() {
        selAnchorCol = selAnchorRow = selEndCol = selEndRow = -1;
    }

    private static boolean isWordChar(TerminalCell cell) {
        char c = cell.character;
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '/' || c == ':' || c == '@' || c == '~' || c == '$';
    }

    private void copyToClipboard(String text) {
        org.eclipse.swt.dnd.Clipboard cb = new org.eclipse.swt.dnd.Clipboard(display);
        cb.setContents(
            new Object[]{ text },
            new org.eclipse.swt.dnd.Transfer[]{ org.eclipse.swt.dnd.TextTransfer.getInstance() }
        );
        cb.dispose();
    }

    private void pasteFromClipboard() {
        if (disconnected || !connection.isConnected()) return;
        org.eclipse.swt.dnd.Clipboard cb = new org.eclipse.swt.dnd.Clipboard(display);
        String text = (String) cb.getContents(org.eclipse.swt.dnd.TextTransfer.getInstance());
        cb.dispose();
        if (text == null || text.isEmpty()) return;

        // Confirm multi-line pastes
        if (text.contains("\n") || text.contains("\r")) {
            if (!confirmMultilinePaste(text)) return;
        }

        // Clear selection on paste
        clearSelection();
        canvas.redraw();

        try {
            connection.send(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {}
    }

    private boolean confirmMultilinePaste(String text) {
        Shell dlg = new Shell(canvas.getShell(), SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM | SWT.RESIZE);
        dlg.setText("Paste confirmation");
        dlg.setSize(520, 340);
        Rectangle pw = canvas.getShell().getBounds();
        Rectangle dw = dlg.getBounds();
        dlg.setLocation(pw.x + (pw.width - dw.width) / 2, pw.y + (pw.height - dw.height) / 2);

        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 14; gl.marginHeight = 12;
        dlg.setLayout(gl);

        Label lbl = new Label(dlg, SWT.WRAP);
        lbl.setText("The text to be pasted contains multiple lines. Paste anyway?");
        lbl.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Text preview = new Text(dlg, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL);
        preview.setText(text);
        preview.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Composite cmpBtns = new Composite(dlg, SWT.NONE);
        cmpBtns.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
        org.eclipse.swt.layout.RowLayout rl = new org.eclipse.swt.layout.RowLayout(SWT.HORIZONTAL);
        rl.spacing = 8;
        cmpBtns.setLayout(rl);
        Button btnPaste  = new Button(cmpBtns, SWT.PUSH); btnPaste.setText("Paste");
        Button btnCancel = new Button(cmpBtns, SWT.PUSH); btnCancel.setText("Cancel");
        dlg.setDefaultButton(btnPaste);

        boolean[] result = { false };
        btnPaste.addListener(SWT.Selection,  e -> { result[0] = true;  dlg.dispose(); });
        btnCancel.addListener(SWT.Selection, e -> { result[0] = false; dlg.dispose(); });
        dlg.addListener(SWT.Close,           e -> result[0] = false);

        dlg.open();
        Display d = dlg.getDisplay();
        while (!dlg.isDisposed()) { if (!d.readAndDispatch()) d.sleep(); }
        return result[0];
    }

    private void notifyActivity() {
        lastActivityTime = System.currentTimeMillis();
        if (disconnected) return;
        display.asyncExec(() -> {
            if (tabItem.isDisposed() || disconnected) return;
            if (tabItem.getParent().getSelection() == tabItem) return;
            activityPending = true;
            if (!blinkRunning) {
                blinkRunning = true;
                blinkPhase   = false;
                scheduleBlink();
            }
        });
    }

    private void scheduleBlink() {
        display.timerExec(BLINK_INTERVAL_MS, () -> {
            if (tabItem.isDisposed() || closed || !activityPending) return;
            long idle = System.currentTimeMillis() - lastActivityTime;
            if (idle >= IDLE_THRESHOLD_MS) {
                // Traffic stopped — stay fixed on dark blue
                tabItem.setImage(getBlueDarkIcon());
                blinkRunning = false;
                return;
            }
            blinkPhase = !blinkPhase;
            tabItem.setImage(blinkPhase ? getBlueLightIcon() : getBlueDarkIcon());
            scheduleBlink();
        });
    }

    /** Called from MainWindow when this tab becomes the active/focused tab. */
    public void clearActivity() {
        activityPending = false;
        blinkRunning    = false;
        display.asyncExec(() -> {
            if (!tabItem.isDisposed() && !disconnected) tabItem.setImage(null);
        });
    }

    private Image getBlueDarkIcon() {
        if (blueDarkIcon != null && !blueDarkIcon.isDisposed()) return blueDarkIcon;
        blueDarkIcon = buildDot(new RGB(30, 90, 170), new RGB(70, 130, 200));
        return blueDarkIcon;
    }

    private Image getBlueLightIcon() {
        if (blueLightIcon != null && !blueLightIcon.isDisposed()) return blueLightIcon;
        blueLightIcon = buildDot(new RGB(80, 160, 240), new RGB(160, 210, 255));
        return blueLightIcon;
    }

    private Image buildDot(RGB fill, RGB highlight) {
        final int SZ = 10;
        PaletteData pal = new PaletteData(0xFF0000, 0x00FF00, 0x0000FF);
        ImageData data  = new ImageData(SZ, SZ, 24, pal);
        data.transparentPixel = pal.getPixel(new RGB(0, 0, 0));
        Image img = new Image(display, data);
        GC gc = new GC(img);
        gc.setBackground(new Color(display, 0, 0, 0));
        gc.fillRectangle(0, 0, SZ, SZ);
        gc.setBackground(new Color(display, fill.red, fill.green, fill.blue));
        gc.fillOval(1, 1, SZ - 2, SZ - 2);
        gc.setForeground(new Color(display, highlight.red, highlight.green, highlight.blue));
        gc.drawArc(2, 2, 3, 3, 45, 90);
        gc.dispose();
        return img;
    }

    private void handleDisconnect() {
        disconnected = true;
        display.asyncExec(() -> {
            if (tabItem.isDisposed()) return;
            tabItem.setImage(getRedDotIcon());
            canvas.redraw();
            if (onStateChanged != null) onStateChanged.run();
        });
    }

    private Image getRedDotIcon() {
        if (redDotIcon != null && !redDotIcon.isDisposed()) return redDotIcon;
        final int SZ = 10;
        PaletteData pal = new PaletteData(0xFF0000, 0x00FF00, 0x0000FF);
        ImageData data  = new ImageData(SZ, SZ, 24, pal);
        data.transparentPixel = pal.getPixel(new RGB(0, 0, 0));
        redDotIcon = new Image(display, data);
        GC gc = new GC(redDotIcon);
        gc.setBackground(new Color(display,   0,   0,   0));
        gc.fillRectangle(0, 0, SZ, SZ);
        gc.setBackground(new Color(display, 210,  50,  50));
        gc.fillOval(1, 1, SZ - 2, SZ - 2);
        gc.setForeground(new Color(display, 255, 120, 120));
        gc.drawArc(2, 2, 3, 3, 45, 90);
        gc.dispose();
        return redDotIcon;
    }

    // -----------------------------------------------------------------------
    // Reconnect
    // -----------------------------------------------------------------------
    public void reconnect(String password) {
        disconnected = false;
        display.asyncExec(() -> {
            if (!tabItem.isDisposed()) {
                tabItem.setImage(null);
                canvas.redraw();
                if (onStateChanged != null) onStateChanged.run();
            }
        });
        startSshThread(sessionInfo, password);
    }

    private void triggerReconnect() {
        if (onReconnectRequest != null) display.asyncExec(onReconnectRequest);
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------
    public SessionInfo getSessionInfo()                   { return sessionInfo; }
    public boolean     isDisconnected()                   { return disconnected; }
    public void        setOnReconnectRequest(Runnable r)  { this.onReconnectRequest = r; }
    /** Called after a tab drag-reorder replaces the underlying CTabItem. */
    public void        replaceTabItem(CTabItem newItem)   { this.tabItem = newItem; }
    public void        setOnStateChanged(Runnable r)      { this.onStateChanged = r; }

    // -----------------------------------------------------------------------
    // Dispose
    // -----------------------------------------------------------------------
    private void disposeOffscreen() {
        if (offscreenBuffer != null && !offscreenBuffer.isDisposed()) {
            offscreenBuffer.dispose();
            offscreenBuffer = null;
        }
    }

    public int[] getAppearance() {
        // returns [fontSize, fgR, fgG, fgB, bgR, bgG, bgB]
        RGB fg = defaultFg.getRGB();
        RGB bg = defaultBg.getRGB();
        return new int[]{ termFontSize, fg.red, fg.green, fg.blue, bg.red, bg.green, bg.blue };
    }

    public void applyAppearance(int newFontSize, org.eclipse.swt.graphics.RGB fg, org.eclipse.swt.graphics.RGB bg) {
        if (canvas.isDisposed()) return;
        termFontSize = newFontSize;
        if (!defaultBg.isDisposed()) defaultBg.dispose();
        if (!defaultFg.isDisposed()) defaultFg.dispose();
        defaultBg = new Color(display, bg);
        defaultFg = new Color(display, fg);
        initFont();
        canvas.setBackground(defaultBg);
        disposeOffscreen();
        updateTerminalSize();
        canvas.redraw();
    }

    public void dispose() {
        closed = true;
        if (altFilter  != null) display.removeFilter(SWT.KeyDown, altFilter);
        if (fKeyFilter != null) display.removeFilter(SWT.KeyDown, fKeyFilter);
        closeLog();
        connection.close();
        display.asyncExec(() -> {
            disposeOffscreen();
            if (redDotIcon   != null && !redDotIcon.isDisposed())   redDotIcon.dispose();
            if (blueDarkIcon != null && !blueDarkIcon.isDisposed()) blueDarkIcon.dispose();
            if (blueLightIcon!= null && !blueLightIcon.isDisposed())blueLightIcon.dispose();
            if (!termFont.isDisposed())  termFont.dispose();
            if (!defaultBg.isDisposed()) defaultBg.dispose();
            if (!defaultFg.isDisposed()) defaultFg.dispose();
        });
    }

    public CTabItem getTabItem() { return tabItem; }
    public Canvas   getCanvas()  { return canvas;  }

    public boolean isLogging()   { return logStream != null; }

    public String getLogDir() {
        SessionInfo s = sessionInfo;
        return (s.logDir != null && !s.logDir.isBlank())
               ? s.logDir
               : System.getProperty("user.home") + "/.14bis/screen_captures";
    }

    public String getLogFileName() {
        return sessionInfo.logFileName != null ? sessionInfo.logFileName : "";
    }

    /** Start logging to the given directory and base filename. Stops any active log first. */
    public void startLogging(String dir, String fileName) {
        closeLog();
        SessionInfo tmp = new SessionInfo();
        tmp.host        = sessionInfo.host;
        tmp.logEnabled  = true;
        tmp.logDir      = dir;
        tmp.logFileName = fileName;
        openLogFile(tmp);
    }

    /** Stop logging immediately. */
    public void stopLogging() { closeLog(); }
}
