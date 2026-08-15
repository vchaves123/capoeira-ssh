package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.model.SessionInfo;
import br.com.capoeirassh.ssh.ssh.SshConnection;
import br.com.capoeirassh.ssh.terminal.TerminalCell;
import br.com.capoeirassh.ssh.terminal.TerminalEmulator;
import br.com.capoeirassh.ssh.terminal.TerminalTrace;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
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
    private Font  termFontBold;   // cached bold variant, rebuilt on font/size change
    /** Supplies a substitute font for code points termFont has no glyph for. */
    private GlyphFallback glyphFallback;
    private Font  overlayFont;    // cached 16pt bold for the disconnected overlay
    private Color colOverlayScrim;// disconnected dim scrim
    private Color colOverlayText; // disconnected "Connection closed" text
    private Color colReconnect;   // disconnected reconnect hint
    private Color colTraceBorder; // red frame drawn while byte tracing is on
    private long  logBytesWritten = 0;
    private static final long MAX_LOG_BYTES = 100L * 1024 * 1024; // cap session log at 100 MB
    private static final int  TRACE_BORDER_WIDTH = 2;             // px, red frame while tracing
    private int   charWidth;
    private int   charHeight;

    private Color defaultBg;
    private Color defaultFg;
    /** The same two colours packed as 0xRRGGBB. Reverse video has to swap concrete colours, and
     *  the cell values are the DEFAULT_COLOR sentinel far more often than not — resolving that
     *  sentinel per cell per frame via Color.getRGB() would allocate inside the render loop. */
    private int   defaultFgRgb;
    private int   defaultBgRgb;
    private int   termFontSize = 12;
    private String termFontName = MonoFonts.DEFAULT;

    private Image offscreenBuffer;

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------
    private volatile boolean closed        = false;
    private volatile boolean disconnected  = false;
    private boolean          cursorBlink   = false;
    private int              scrollOffset  = 0;    // 0 = pinned to bottom
    /** Guards against flooding the UI event queue with one asyncExec per processBytes() call
     *  during a fast stream — see the changeListener in the constructor. */
    private final java.util.concurrent.atomic.AtomicBoolean redrawPending =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private final SessionInfo sessionInfo;
    private String            tabTitle;
    private String            textNormal;   // padded so its width matches textBold
    private String            textBold;
    private Font              boldTabFont;
    private Color             colorActivityBlue;
    private Color             colorDisconnectedRed;

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
    /** Selection anchor (col, absolute buffer row — see {@link TerminalEmulator#getCellAbs});
     *  -1 = no selection. Stored as an absolute row (not viewport-relative) so the selected
     *  text stays anchored to the same content as the user scrolls the scrollback instead of
     *  visually detaching from it. */
    private int selAnchorCol = -1, selAnchorRow = -1;
    /** Selection end — updated as the mouse moves. Same absolute-row coordinate space. */
    private int selEndCol    = -1, selEndRow    = -1;
    /** True while the trailing MouseUp of a double-click should be ignored. */
    private boolean suppressNextMouseUp = false;

    /** Pending debounced resize runnable; cancelled and rescheduled on every SWT.Resize event. */
    private Runnable pendingResize;

    /** Overlay shown over the main window while its border is being dragged, so the user can see
     *  the terminal size (in cols x lines, the same numbers sent to the remote PTY) they're
     *  resizing to. Created lazily, reused across drags rather than recreated per tick — SWT.Resize
     *  fires on every pixel — and hidden (not disposed) once the resize settles. */
    private Shell resizeIndicator;
    private Label resizeIndicatorLabel;

    /** Overlay for the brief "Copied" confirmation — see {@link #showCopiedIndicator}. */
    private Shell copiedIndicator;
    /** Overlay for the brief "State dumped" confirmation — see {@link #showTraceDumpIndicator}. */
    private Shell traceDumpIndicator;
    /** False until the tab's initial layout has settled. A brand-new tab's canvas gets its real
     *  size synchronously — via {@code tabFolder.setSelection(...)}, called by MainWindow right
     *  after construction, in the same call stack — which fires SWT.Resize exactly like a user
     *  drag would. Arming this only from an asyncExec queued at the end of the constructor means
     *  it flips true on a later dispatch tick, after that synchronous initial layout is already
     *  done — so the indicator never flashes on tab creation, but still shows normally for any
     *  resize that happens once the tab is actually up and running (drag, maximize, restore). */
    private boolean resizeIndicatorArmed = false;
    /** Shell maximized/minimized state as of the last resize tick, so a maximize/restore/minimize
     *  click — which fires a resize exactly like a border drag does — can be told apart from one.
     *  Lazily initialised (null) so the very first tick, whatever it is, is never mistaken for a
     *  transition. */
    private Boolean lastShellMaximized;
    private Boolean lastShellMinimized;

    // -----------------------------------------------------------------------
    // Logging
    // -----------------------------------------------------------------------
    private OutputStream logStream;
    private static final DateTimeFormatter LOG_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final java.util.concurrent.atomic.AtomicInteger LOG_SEQ = new java.util.concurrent.atomic.AtomicInteger();

    // -----------------------------------------------------------------------
    // Byte-level trace (debugging aid, off by default and never persisted)
    // -----------------------------------------------------------------------
    /** Non-null exactly while tracing is on. Written from both the SSH reader thread (RX) and the
     *  UI thread (TX/STATE); {@link TerminalTrace} serializes its own writes. Volatile so the
     *  reader thread sees the UI thread turning tracing off without going through a lock on the
     *  hot receive path. */
    private volatile TerminalTrace trace;

    // State machine for stripping ANSI escape sequences from the log stream.
    private enum AnsiState { NORMAL, ESC, ESC_INTERMEDIATE, CSI, OSC, OSC_ESC }
    private AnsiState ansiState = AnsiState.NORMAL;
    /** Remaining UTF-8 continuation bytes expected — needed to tell a genuine multi-byte
     *  character apart from an 8-bit C1 control byte (0x80-0x9F overlaps both ranges). */
    private int ansiUtf8Remaining = 0;

    /** Last window title this tab's connection asked for via OSC 0/2, or {@code null} if none
     *  arrived yet. Applied to the main window's title bar only while this tab is selected. */
    private volatile String remoteTitle;

    /** Visible phase of the SGR-5 blink attribute: false hides the glyphs of blinking cells.
     *  Its timer only runs while blinking text is actually on screen (see {@link #render}), so a
     *  session that never uses the attribute — almost all of them — pays nothing for it. */
    private boolean textBlinkOn      = true;
    private boolean textBlinkRunning = false;
    private static final int TEXT_BLINK_MS = 500;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------
    public TerminalTab(CTabFolder folder, SessionInfo info, char[] password) {
        this.display     = folder.getDisplay();
        this.sessionInfo = info;
        this.tabTitle    = info.label();

        tabItem = new CTabItem(folder, SWT.CLOSE);
        computeTabTexts();
        tabItem.setText(textNormal);

        canvas = new Canvas(folder, SWT.NO_BACKGROUND | SWT.V_SCROLL);
        tabItem.setControl(canvas);

        setDefaultColors(new Color(display, 255, 176, 0), // classic amber phosphor
                         new Color(display, 0,   0,   0));

        initFont();

        emulator = new TerminalEmulator(80, 24);
        emulator.setChangeListener(() -> {
            // Coalesce bursts into at most one pending asyncExec. The SSH reader thread calls
            // this once per socket read — during a fast stream (e.g. a CLI printing many small
            // updates back to back) that can fire dozens of times a second. Scheduling a fresh
            // asyncExec every time floods the Win32 message queue with normal-priority posted
            // messages, and WM_PAINT — the actual repaint — is only delivered once that queue
            // goes idle. A continuous stream of updates can starve it for minutes: canvas.redraw()
            // keeps marking the widget dirty, but Windows never gets a quiet moment to paint it.
            if (redrawPending.compareAndSet(false, true)) {
                display.asyncExec(() -> {
                    redrawPending.set(false);
                    if (canvas.isDisposed()) return;
                    if (hasSelection()) clearSelection();
                    canvas.redraw();
                });
            }
        });

        connection = new SshConnection();

        // Deliver queued terminal responses (DSR, DA, XTWINOPS) to SSH.
        // flushResponses() is called from readSsh() after each processBytes(),
        // outside the synchronized lock, so this callback never blocks SSH reads.
        emulator.setDataListener(this::sendToServer);

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

        emulator.setAllowColumnMode(info.allowColumnMode);
        emulator.setColumnModeListener(cols -> display.asyncExec(() -> resizeWindowToColumns(cols)));

        // OSC 0/2 "set window/icon title" — reflect it in the main window's title bar, but only
        // while this tab is the one actually selected (a background tab's title change must not
        // steal the shell title out from under whatever tab the user is looking at).
        emulator.setTitleListener(title -> display.asyncExec(() -> {
            remoteTitle = title;
            applyTitleIfActive();
        }));

        setupCanvas();
        setupScrollBar();
        startCursorBlink();

        startSshThread(info, password);

        // See resizeIndicatorArmed's javadoc: queued here so it flips true only after the
        // synchronous initial-layout resize the caller (MainWindow) is about to trigger.
        display.asyncExec(() -> resizeIndicatorArmed = true);
    }

    // -----------------------------------------------------------------------
    // Font
    // -----------------------------------------------------------------------
    private void initFont() {
        String fontName = MonoFonts.resolve(display, termFontName);
        if (termFont != null && !termFont.isDisposed()) termFont.dispose();
        if (termFontBold != null && !termFontBold.isDisposed()) termFontBold.dispose();
        if (overlayFont  != null && !overlayFont.isDisposed())  overlayFont.dispose();
        termFontBold = null;   // rebuilt lazily in render() for the new font/size
        overlayFont  = null;
        termFont = new Font(display, fontName, termFontSize, SWT.NORMAL);

        GC gc = new GC(display);
        gc.setFont(termFont);
        Point sz = gc.stringExtent("W");
        charWidth  = sz.x;
        charHeight = gc.getFontMetrics().getHeight();
        gc.dispose();

        // Coverage and substitute fonts are both specific to this family/size pair, and the
        // substitutes have to be sized against the cell — so this must come after the measurement.
        if (glyphFallback != null) glyphFallback.dispose();
        glyphFallback = new GlyphFallback(display, fontName, termFontSize, charWidth);
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
            updateResizeIndicator();
            if (pendingResize != null) display.timerExec(-1, pendingResize);
            pendingResize = () -> {
                pendingResize = null;
                if (!canvas.isDisposed()) updateTerminalSize();
                hideResizeIndicator();
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
            if (e.button == 1 && e.count >= 3) {
                // Triple click: select the whole line, trimmed to its last non-blank
                // character — selecting all the way to the terminal's right edge would copy
                // every row padded with trailing spaces out to the full column count.
                int row = Math.max(0, Math.min(e.y / charHeight, visibleRows() - 1));
                int cols = emulator.getCols();
                int endCol = cols - 1;
                while (endCol > 0) {
                    TerminalCell c = emulator.getCell(row, endCol, scrollOffset);
                    if (c != null && c.character != ' ' && c.character != '\0') break;
                    endCol--;
                }
                int absRow = toAbsRow(row);
                selAnchorCol = 0;      selAnchorRow = absRow;
                selEndCol    = endCol; selEndRow    = absRow;
                suppressNextMouseUp = true;
                canvas.redraw();
            } else if (e.button == 1) {
                // Start selection
                selAnchorCol = e.x / charWidth;
                selAnchorRow = toAbsRow(e.y / charHeight);
                selEndCol    = selAnchorCol;
                selEndRow    = selAnchorRow;
                canvas.redraw();
            } else if (e.button == 3) {
                // cmd.exe convention: right-click copies the current selection and clears it if
                // there is one; with nothing selected, it pastes instead. One button does both
                // jobs depending on state, rather than needing a separate paste gesture.
                if (hasSelection()) {
                    String text = getSelectedText();
                    if (text != null && !text.isEmpty()) { copyToClipboard(text); showCopiedIndicator(); }
                    clearSelection();
                    canvas.redraw();
                } else {
                    pasteFromClipboard();
                }
            }
        });

        canvas.addListener(SWT.MouseMove, e -> {
            if (selAnchorCol < 0 || (e.stateMask & SWT.BUTTON1) == 0) return;
            selEndCol = Math.max(0, Math.min(e.x / charWidth, emulator.getCols() - 1));

            // Auto-scroll while dragging past the top/bottom edge of the visible area, so
            // text that has already scrolled off-screen becomes reachable mid-selection
            // instead of being un-copyable once it leaves the viewport.
            int viewportRow = e.y / charHeight;
            if (viewportRow < 0) {
                scroll(viewportRow);
                viewportRow = 0;
            } else if (viewportRow >= visibleRows()) {
                if (scrollOffset > 0) scroll(viewportRow - visibleRows() + 1);
                viewportRow = visibleRows() - 1;
            }
            selEndRow = toAbsRow(viewportRow);
            canvas.redraw();
        });

        canvas.addListener(SWT.MouseUp, e -> {
            if (e.button != 1 || selAnchorCol < 0) return;
            if (suppressNextMouseUp) { suppressNextMouseUp = false; return; }
            selEndCol = Math.max(0, Math.min(e.x / charWidth,  emulator.getCols() - 1));
            selEndRow = toAbsRow(Math.max(0, Math.min(e.y / charHeight, visibleRows() - 1)));
            // cmd.exe style: finishing a drag only marks the selection (shown in reverse video);
            // right-click is what copies it (see MouseDown). A zero-width selection (a plain
            // click) still clears, so a stray click doesn't leave a phantom highlight.
            String text = getSelectedText();
            if (text == null || text.isEmpty()) clearSelection();
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
            int absRow = toAbsRow(row);
            selAnchorCol = startCol; selAnchorRow = absRow;
            selEndCol    = endCol;   selEndRow    = absRow;
            suppressNextMouseUp = true;
            canvas.redraw();
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
                sendToServer(seq);
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
                if (!disconnected) sendToServer(seq);
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
    /**
     * Grows or shrinks the main window so this tab's canvas ends up exactly {@code targetCols}
     * characters wide — the visible half of DECCOLM (the emulator has already resized its buffer
     * by the time we get here).
     *
     * Only acts for the tab the user is actually looking at: tabs share one window, so honouring
     * this for a background tab would resize the window out from under the foreground one.
     * The width is clamped to the monitor's work area — if 132 columns don't physically fit, the
     * window grows as far as it can and the debounced SWT.Resize that follows will settle the
     * emulator back to whatever actually fits.
     */
    private void resizeWindowToColumns(int targetCols) {
        if (canvas.isDisposed() || charWidth <= 0) return;
        if (tabItem.isDisposed() || tabItem.getParent().getSelection() != tabItem) return;
        Shell shell = canvas.getShell();
        if (shell == null || shell.isDisposed() || shell.getMaximized()) return;

        // Window chrome (tab bar, borders, scrollbar) is whatever the shell has beyond the
        // canvas's client area — measure it rather than assuming a fixed inset.
        Rectangle shellBounds = shell.getBounds();
        int chromeWidth = shellBounds.width - canvas.getClientArea().width;
        int wanted      = chromeWidth + targetCols * charWidth;

        Rectangle screen = shell.getMonitor().getClientArea();
        shell.setSize(Math.min(wanted, screen.width), shellBounds.height);
    }

    private void updateTerminalSize() {
        if (canvas.isDisposed() || charWidth == 0 || charHeight == 0) return;
        Rectangle r = canvas.getClientArea();
        int newCols = Math.max(1, r.width  / charWidth);
        int newRows = Math.max(1, r.height / charHeight);
        emulator.resize(newCols, newRows);
        if (connection.isConnected())
            connection.updatePtySize(newCols, newRows, r.width, r.height);
    }

    /** Shows (or updates) the "cols x lines" overlay while the window border is being dragged —
     *  only for the tab actually on screen, since every open tab shares one window and a
     *  background tab's canvas shouldn't pop an indicator over whatever the user is looking at.
     *  Deliberately excludes maximize/restore/minimize: those fire a resize exactly like a border
     *  drag would, but showing the overlay for a single instant jump (rather than a live drag)
     *  looked wrong in practice, so any tick where the maximized/minimized state just changed is
     *  treated as one of those, not a drag, and skipped. */
    private void updateResizeIndicator() {
        if (!resizeIndicatorArmed) return;
        if (canvas.isDisposed() || charWidth <= 0 || charHeight <= 0) return;
        if (tabItem.isDisposed() || tabItem.getParent().getSelection() != tabItem) return;
        Shell shell = canvas.getShell();
        if (shell == null || shell.isDisposed()) return;

        boolean maximized = shell.getMaximized();
        boolean minimized  = shell.getMinimized();
        boolean transition = (lastShellMaximized != null && lastShellMaximized != maximized)
                           || (lastShellMinimized != null && lastShellMinimized != minimized);
        lastShellMaximized = maximized;
        lastShellMinimized = minimized;
        if (transition || maximized || minimized) { hideResizeIndicator(); return; }

        Rectangle r = canvas.getClientArea();
        int liveCols = Math.max(1, r.width  / charWidth);
        int liveRows = Math.max(1, r.height / charHeight);

        if (resizeIndicator == null || resizeIndicator.isDisposed()) {
            resizeIndicator = new Shell(shell, SWT.NO_TRIM | SWT.ON_TOP);
            Color bg = new Color(display, 30, 30, 30);
            Color fg = new Color(display, 230, 230, 230);
            resizeIndicator.setBackground(bg);
            Font indicatorFont = new Font(display, termFont.getFontData()[0].getName(), 16, SWT.BOLD);
            resizeIndicator.addDisposeListener(e -> { bg.dispose(); fg.dispose(); indicatorFont.dispose(); });

            GridLayout gl = new GridLayout(1, false);
            gl.marginWidth = 16; gl.marginHeight = 10;
            resizeIndicator.setLayout(gl);

            resizeIndicatorLabel = new Label(resizeIndicator, SWT.NONE);
            resizeIndicatorLabel.setBackground(bg);
            resizeIndicatorLabel.setForeground(fg);
            resizeIndicatorLabel.setFont(indicatorFont);
        }

        resizeIndicatorLabel.setText(liveCols + " × " + liveRows);
        resizeIndicator.pack();
        Rectangle sb = shell.getBounds();
        Point sz = resizeIndicator.getSize();
        resizeIndicator.setLocation(sb.x + (sb.width - sz.x) / 2, sb.y + (sb.height - sz.y) / 2);
        if (!resizeIndicator.isVisible()) resizeIndicator.setVisible(true);
    }

    private void hideResizeIndicator() {
        if (resizeIndicator != null && !resizeIndicator.isDisposed()) resizeIndicator.setVisible(false);
    }

    /** Brief "Copied" confirmation shown right after a right-click copies a selection to the
     *  clipboard — reuses the same borderless-overlay look as the resize indicator, but
     *  auto-dismisses on a timer instead of staying up for as long as a drag continues. */
    private void showCopiedIndicator() {
        copiedIndicator = showBriefIndicator(copiedIndicator, "Copied");
    }

    /** Brief confirmation shown when Ctrl+Shift+D records a state snapshot into the trace —
     *  otherwise there is no feedback at all that the key press actually did anything. */
    private void showTraceDumpIndicator() {
        traceDumpIndicator = showBriefIndicator(traceDumpIndicator, "State dumped to trace");
    }

    /** Shows a small borderless "toast" with {@code text} near the bottom of the window for
     *  ~900ms, then auto-hides. {@code indicator} is created once and reused on later calls
     *  (each caller keeps its own field so two different confirmations never fight over one
     *  widget); returns the (possibly newly created) shell for the caller to store back. */
    private Shell showBriefIndicator(Shell indicator, String text) {
        if (canvas.isDisposed()) return indicator;
        Shell shell = canvas.getShell();
        if (shell == null || shell.isDisposed()) return indicator;

        if (indicator == null || indicator.isDisposed()) {
            indicator = new Shell(shell, SWT.NO_TRIM | SWT.ON_TOP);
            Color bg = new Color(display, 30, 30, 30);
            Color fg = new Color(display, 230, 230, 230);
            indicator.setBackground(bg);
            Font indicatorFont = new Font(display, termFont.getFontData()[0].getName(), 12, SWT.BOLD);
            indicator.addDisposeListener(e -> { bg.dispose(); fg.dispose(); indicatorFont.dispose(); });

            GridLayout gl = new GridLayout(1, false);
            gl.marginWidth = 14; gl.marginHeight = 8;
            indicator.setLayout(gl);

            Label lbl = new Label(indicator, SWT.NONE);
            lbl.setText(text);
            lbl.setBackground(bg);
            lbl.setForeground(fg);
            lbl.setFont(indicatorFont);
        } else {
            ((Label) indicator.getChildren()[0]).setText(text);
        }

        indicator.pack();
        Rectangle sb = shell.getBounds();
        Point sz = indicator.getSize();
        // Near the bottom rather than dead centre, so it doesn't sit on top of whatever text
        // was just selected/read on screen.
        indicator.setLocation(sb.x + (sb.width - sz.x) / 2, sb.y + sb.height - sz.y - 60);
        indicator.setVisible(true);

        Shell toHide = indicator;
        display.timerExec(900, () -> {
            if (!toHide.isDisposed()) toHide.setVisible(false);
        });
        return indicator;
    }

    // -----------------------------------------------------------------------
    // Rendering  (double-buffered via off-screen Image)
    // -----------------------------------------------------------------------
    private void render(GC screen) {
        Rectangle area = canvas.getClientArea();
        if (area.width <= 0 || area.height <= 0) return;

        boolean sawBlinkingCell = false;

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

            // Selection bounds, reprojected onto the current viewport (selection is stored as
            // absolute buffer rows, which move as scrollOffset changes) — computed once here
            // rather than per cell. selRow0/1 stay -1 when there is no selection or it's
            // scrolled entirely out of view, so the per-cell check below is a single comparison.
            int selRow0 = -1, selCol0 = 0, selRow1 = -1, selCol1 = 0;
            if (selAnchorCol >= 0 && hasSelection()) {
                int[] norm = normalizedSelection();
                selRow0 = fromAbsRow(norm[0]); selCol0 = norm[1];
                selRow1 = fromAbsRow(norm[2]); selCol1 = norm[3];
            }

            for (int r = 0; r < rows; r++) {
                int lastColBg = -1; // rightmost column's own bg, for right-margin fill
                for (int c = 0; c < cols; c++) {
                    TerminalCell cell = emulator.getCell(r, c, scrollOffset);
                    if (cell == null) continue;

                    int fg = TerminalEmulator.resolveColor(cell.fgColor);
                    int bg = TerminalEmulator.resolveColor(cell.bgColor);

                    // Reverse video swaps foreground and background. resolveColor returns -1 for
                    // "the terminal default", and swapping -1 with -1 is a no-op — which is why
                    // reverse used to render identically to normal text whenever the cell kept
                    // the default colours (the common case: tput rev, status bars, vttest's
                    // "negative"). Resolve the sentinel to the real colour before swapping.
                    boolean rev = cell.reverse;
                    if (rev) {
                        int[] swapped = swapForReverseVideo(fg, bg, defaultFgRgb, defaultBgRgb);
                        fg = swapped[0]; bg = swapped[1];
                    }

                    // cmd.exe-style selection: swap fg/bg, same as reverse video, rather than a
                    // translucent tint over already-rendered text — so a selected cell reads
                    // exactly the way selected text does in a real console. Applying it after
                    // SGR reverse (rather than instead of it) means a selected cell that was
                    // already reverse-video correctly reverses back to normal-looking colours,
                    // matching how a real terminal composes the two.
                    if (selRow0 >= 0 && r >= selRow0 && r <= selRow1
                            && (r > selRow0 || c >= selCol0) && (r < selRow1 || c <= selCol1)) {
                        int[] swapped = swapForReverseVideo(fg, bg, defaultFgRgb, defaultBgRgb);
                        fg = swapped[0]; bg = swapped[1];
                    }

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
                    }
                    // The fractional-column gap at the right must match the actual rightmost
                    // cell's own background — not whichever earlier column last had an explicit
                    // colour — so a colour run that ends mid-row doesn't bleed into the border.
                    if (c == cols - 1 && !isCursor) lastColBg = bg;

                    // SGR 5: the glyph is hidden on the off phase, but its background still gets
                    // painted above, so a blinking cell pulses its text rather than its block.
                    if (cell.blink) {
                        sawBlinkingCell = true;
                        if (!textBlinkOn) { if (c == cols - 1 && !isCursor) lastColBg = bg; continue; }
                    }

                    // The trailer half of a double-width glyph carries no character of its own —
                    // its background was painted above (so a wide glyph's highlight covers both
                    // columns), but the glyph itself is drawn from the leading cell.
                    if (cell.character != ' ' && cell.character != '\0' && !cell.wideTrailer) {
                        String glyph = glyphOf(cell.character);
                        Color cfg = fg >= 0 ? swtRgb(fg) : null;
                        gc.setForeground(cfg != null ? cfg : defaultFg);

                        // A code point the terminal font has no glyph for is drawn with a
                        // substitute font instead of a tofu box (see GlyphFallback).
                        Font drawFont = glyphFallback != null
                                ? glyphFallback.fontFor(cell.character, cell.bold) : null;
                        if (drawFont == null && cell.bold) {
                            // Reuse a cached bold font — creating and disposing a native Font per
                            // bold cell per frame let a hostile server churn GDI/X11 font handles
                            // and freeze the whole UI thread.
                            if (termFontBold == null || termFontBold.isDisposed())
                                termFontBold = new Font(display, termFont.getFontData()[0].getName(), termFontSize, SWT.BOLD);
                            drawFont = termFontBold;
                        }
                        if (drawFont != null) {
                            gc.setFont(drawFont);
                            // Substitute glyphs are sized to fit the cell but keep the substitute
                            // font's own side bearings, so centre them in the columns they own
                            // rather than hanging them off the left edge.
                            int gx = glyphFallback != null ? glyphFallback.xOffsetFor(cell.character) : 0;
                            gc.drawString(glyph, px + gx, py, true);
                            gc.setFont(termFont);
                        } else {
                            gc.drawString(glyph, px, py, true);
                        }

                        if (cell.underline) {
                            gc.drawLine(px, py + charHeight - 1, px + charWidth - 1, py + charHeight - 1);
                        }

                        if (cfg != null) cfg.dispose();
                    }
                }
                // Extend last cell's background into the fractional-column gap at the right.
                if (rightMargin < area.width && lastColBg >= 0) {
                    Color cbg = swtRgb(lastColBg);
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
            if (disconnected) {
                // Semi-transparent gray overlay
                gc.setAlpha(180);
                if (colOverlayScrim == null || colOverlayScrim.isDisposed())
                    colOverlayScrim = new Color(display, 20, 20, 20);
                gc.setBackground(colOverlayScrim);
                gc.fillRectangle(area);
                gc.setAlpha(255);

                if (overlayFont == null || overlayFont.isDisposed())
                    overlayFont = new Font(display, termFont.getFontData()[0].getName(), 16, SWT.BOLD);
                gc.setFont(overlayFont);
                if (colOverlayText == null || colOverlayText.isDisposed())
                    colOverlayText = new Color(display, 200, 200, 200);
                gc.setForeground(colOverlayText);

                String line1 = "Connection closed";
                Point e1 = gc.stringExtent(line1);
                gc.drawString(line1, (area.width - e1.x) / 2, area.height / 2 - e1.y - 6, true);

                gc.setFont(termFont);
                if (colReconnect == null || colReconnect.isDisposed())
                    colReconnect = new Color(display, 100, 180, 255);
                gc.setForeground(colReconnect);
                String line2 = "▶  Click to reconnect";
                Point e2 = gc.stringExtent(line2);
                gc.drawString(line2, (area.width - e2.x) / 2, area.height / 2 + 6, true);
            }

            // Red frame while tracing, so a session quietly writing every byte to disk is never
            // mistaken for a normal one. Drawn last, over everything including the disconnected
            // scrim. Inset by half the line width because SWT centers the stroke on the path, so
            // drawing exactly on the bounds would clip the outer half of it.
            if (trace != null) {
                if (colTraceBorder == null || colTraceBorder.isDisposed())
                    colTraceBorder = new Color(display, 220, 40, 40);
                gc.setForeground(colTraceBorder);
                gc.setLineWidth(TRACE_BORDER_WIDTH);
                int inset = TRACE_BORDER_WIDTH / 2;
                gc.drawRectangle(inset, inset,
                                 area.width  - TRACE_BORDER_WIDTH,
                                 area.height - TRACE_BORDER_WIDTH);
                gc.setLineWidth(1);
            }
        } finally {
            gc.dispose();
        }

        screen.drawImage(offscreenBuffer, 0, 0);
        updateScrollBar();
        syncTextBlinkTimer(sawBlinkingCell);
    }

    /** Starts the blink timer the moment blinking text appears on screen and stops it once none
     *  is left, so the repaint it costs is only paid while something is actually blinking. */
    private void syncTextBlinkTimer(boolean blinkingVisible) {
        if (blinkingVisible == textBlinkRunning) return;
        textBlinkRunning = blinkingVisible;
        if (!blinkingVisible) {
            textBlinkOn = true;   // leave the text visible when it stops blinking
            return;
        }
        Runnable tick = new Runnable() {
            @Override public void run() {
                if (canvas.isDisposed() || !textBlinkRunning) return;
                textBlinkOn = !textBlinkOn;
                canvas.redraw();
                display.timerExec(TEXT_BLINK_MS, this);
            }
        };
        display.timerExec(TEXT_BLINK_MS, tick);
    }

    /**
     * Swaps {@code fg}/{@code bg} for reverse video (SGR 7) or cmd.exe-style selection
     * highlight, resolving the "terminal default" sentinel ({@link TerminalEmulator#DEFAULT_COLOR}
     * as returned by {@link TerminalEmulator#resolveColor}, i.e. {@code < 0}) to a real RGB
     * first — swapping two {@code -1} sentinels is a no-op, which is why reverse video used to
     * render identically to normal text whenever a cell kept the default colours (the common
     * case: {@code tput rev}, status bars, vttest's "negative" — fixed in build 230).
     *
     * <p>Package-private and static (no {@code Display}/widget needed) so a test can drive the
     * actual colour math directly, without constructing a real {@code TerminalTab}.
     *
     * @return {@code {newFg, newBg}}
     */
    static int[] swapForReverseVideo(int fg, int bg, int defaultFgRgb, int defaultBgRgb) {
        int realFg = (fg < 0) ? defaultFgRgb : fg;
        int realBg = (bg < 0) ? defaultBgRgb : bg;
        return new int[]{ realBg, realFg };
    }

    /** A cell's code point as a drawable string. BMP code points take the single-char fast path;
     *  only the rare non-BMP ones (emoji, Nerd Fonts' U+F0000 icon range) need a surrogate pair.
     *  This runs per visible cell per frame, hence the fast path. */
    private static String glyphOf(int codePoint) {
        return codePoint < 0x10000
            ? String.valueOf((char) codePoint)
            : new String(Character.toChars(codePoint));
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
                if (disconnected) { cursorBlink = false; canvas.redraw(); return; }
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
    /**
     * The single funnel every byte this tab sends to the server goes through.
     *
     * <p>Centralizing it means the byte trace records outbound traffic in exactly one place, so a
     * send path added later cannot silently escape it, and the connected-check plus the
     * "a failed write just means the session is gone" IOException handling aren't repeated at each
     * of the half-dozen call sites. Safe to call from any thread: {@code connection.send()} is
     * itself synchronized, and {@link TerminalTrace} serializes its own writes.
     */
    private void sendToServer(byte[] data) {
        if (data == null || data.length == 0) return;
        if (!connection.isConnected()) return;
        TerminalTrace t = trace;
        if (t != null) t.tx(data);
        try { connection.send(data); } catch (IOException ignored) {}
    }

    private void handleKey(org.eclipse.swt.events.KeyEvent e) {
        // Alt+key is handled entirely by altFilter — skip here to avoid double-send
        if ((e.stateMask & SWT.ALT) != 0) return;

        // Ctrl+Shift+D dumps the emulator's state into the trace, interleaved with the byte
        // records around it. Only swallowed while tracing is on: with tracing off there is no file
        // to write to, so the key belongs to the remote program like any other.
        if (trace != null
            && (e.stateMask & SWT.CTRL) != 0 && (e.stateMask & SWT.SHIFT) != 0
            && (e.keyCode == 'd' || e.keyCode == 'D')) {
            dumpTraceState();
            return;
        }

        if (scrollOffset != 0) { scrollOffset = 0; updateScrollBar(); }
        if (hasSelection()) { clearSelection(); canvas.redraw(); }

        sendToServer(mapKey(e));
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

        if (ch == SWT.BS || key == SWT.BS) return new byte[]{ (byte) sessionInfo.backspaceCode };
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
    private void startSshThread(SessionInfo info, char[] password) {
        Thread t = new Thread(() -> runSsh(info, password), "ssh-" + info.label());
        t.setDaemon(true);
        t.start();
    }

    private void openLogFile(SessionInfo info) {
        if (!info.logEnabled) return;
        try {
            String defaultDir = System.getProperty("user.home") + "/.capoeira/screen_captures";
            Path   userHome   = Path.of(System.getProperty("user.home")).normalize().toAbsolutePath();
            Path   logDir;
            if (info.logDir != null && !info.logDir.isBlank()) {
                Path candidate = Path.of(info.logDir).normalize().toAbsolutePath();
                logDir = candidate.startsWith(userHome) ? candidate : Path.of(defaultDir);
            } else {
                logDir = Path.of(defaultDir);
            }
            br.com.capoeirassh.ssh.storage.SecureFiles.createDirectories(logDir);

            // Re-check containment against the *real* (symlink/junction-resolved) path.
            // The lexical startsWith() check above only compares normalized path text,
            // so a symlink/NTFS junction planted under the home directory — e.g. via an
            // imported *.session file with an unvalidated logDir — could otherwise point
            // the actual write target outside the intended sandbox despite passing it.
            Path realLogDir   = logDir.toRealPath();
            Path realUserHome = userHome.toRealPath();
            if (!realLogDir.startsWith(realUserHome)) {
                logDir = Path.of(defaultDir);
                br.com.capoeirassh.ssh.storage.SecureFiles.createDirectories(logDir);
            }

            String ts       = LocalDateTime.now().format(LOG_TS);
            String baseName = (info.logFileName != null && !info.logFileName.isBlank())
                              ? info.logFileName.replaceAll("[^\\w\\-.]", "_")
                              : info.host.replaceAll("[^\\w\\-.]", "_");
            String candidate = ts + "_" + baseName;
            Path   file = logDir.resolve(candidate + ".log");
            if (Files.exists(file))
                file = logDir.resolve(candidate + "_" + LOG_SEQ.incrementAndGet() + ".log");
            logStream  = br.com.capoeirassh.ssh.storage.SecureFiles.openAppend(file);
            logBytesWritten = 0;
            ansiState  = AnsiState.NORMAL;
            ansiUtf8Remaining = 0;
        } catch (IOException e) {
            logStream = null;
        }
    }

    private void writeLog(byte[] buf, int len) {
        if (logStream == null) return;
        byte[] filtered = stripAnsi(buf, len);
        if (filtered.length == 0) return;
        // Cap the session log so a hostile server streaming endless output can't fill the disk.
        if (logBytesWritten + filtered.length > MAX_LOG_BYTES) { closeLog(); return; }
        try { logStream.write(filtered); logStream.flush(); logBytesWritten += filtered.length; }
        catch (IOException ignored) { logStream = null; }
    }

    private byte[] stripAnsi(byte[] buf, int len) {
        byte[] out = new byte[len];
        int    w   = 0;
        for (int i = 0; i < len; i++) {
            int b = buf[i] & 0xFF;
            switch (ansiState) {
                case NORMAL:
                    if (ansiUtf8Remaining > 0) {
                        if ((b & 0xC0) == 0x80) {
                            // continuation byte of a multi-byte UTF-8 character — not a C1 control
                            out[w++] = (byte) b;
                            ansiUtf8Remaining--;
                            break;
                        }
                        ansiUtf8Remaining = 0; // invalid sequence — fall through and reprocess b fresh
                    }
                    if (b == 0x1B) {
                        ansiState = AnsiState.ESC;
                    } else if (b >= 0x80 && b <= 0x9F) {
                        // 8-bit C1 control — the terminal emulator (TerminalEmulator.processC1)
                        // treats these as CSI/OSC/etc. introducers just like 7-bit ESC sequences.
                        // Strip them the same way so a crafted C1 sequence can't smuggle raw
                        // escape bytes into the plaintext log.
                        switch (b) {
                            case 0x9B -> ansiState = AnsiState.CSI;
                            case 0x90, 0x98, 0x9D, 0x9E, 0x9F -> ansiState = AnsiState.OSC;
                            default -> {} // other C1 controls (IND/NEL/RI/ST...) — discard, stay NORMAL
                        }
                    } else if (b == '\r' || b == '\n' || b == '\t') {
                        out[w++] = (byte) b;
                    } else if (b >= 0x20 && b < 0x7F) {
                        out[w++] = (byte) b;
                    } else if ((b & 0xE0) == 0xC0) { out[w++] = (byte) b; ansiUtf8Remaining = 1; }
                    else if ((b & 0xF0) == 0xE0)   { out[w++] = (byte) b; ansiUtf8Remaining = 2; }
                    else if ((b & 0xF8) == 0xF0)   { out[w++] = (byte) b; ansiUtf8Remaining = 3; }
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

    private void runSsh(SessionInfo info, char[] password) {
        try {
            connection.connect(info, password, display, this::showVerboseLine); // zeroes password internally
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

    /** Prints one SSH protocol log line (see {@link SshConnection#connect}) directly into the
     *  terminal, dimmed, as the handshake happens — the {@code sshVerbose} option's UI. Called
     *  from whatever thread JSch's logger fires on, so the emulator update (thread-safe) and the
     *  repaint are dispatched onto the UI thread rather than assumed to already be on it. */
    private void showVerboseLine(String line) {
        display.asyncExec(() -> {
            if (canvas.isDisposed()) return;
            byte[] bytes = ("[90m*** ssh: " + sanitizeVerboseLine(line) + "[0m\r\n").getBytes(StandardCharsets.UTF_8);
            emulator.processBytes(bytes);
            canvas.redraw();
        });
    }

    /** Strips control/escape characters before a JSch log line is embedded in the ANSI-wrapped
     *  string above and fed to the terminal. Some log messages are built from server-controlled
     *  text (banner, disconnect reason, etc.), so without this a malicious/MITM server could
     *  inject its own escape/control sequences into the local terminal via the verbose-
     *  diagnostics feature. Embedded CR/LF become a space so one message can't fake multiple
     *  lines of output either. */
    private static String sanitizeVerboseLine(String line) {
        if (line == null) return "";
        StringBuilder out = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\r' || c == '\n') { out.append(' '); continue; }
            if (c < 0x20 || (c >= 0x7F && c <= 0x9F)) continue; // C0/DEL/C1 control range
            out.append(c);
        }
        return out.toString();
    }

    private void readSsh() {
        try {
            InputStream in  = connection.getInputStream();
            byte[]      buf = new byte[4096];
            int         n;
            while (!closed && (n = in.read(buf)) != -1) {
                writeLog(buf, n);
                TerminalTrace t = trace;
                if (t != null) t.rx(buf, 0, n);
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

    /** Converts a viewport-relative row (as seen right now, with the current scrollOffset)
     *  into an absolute buffer row — see {@link TerminalEmulator#getCellAbs}. */
    private int toAbsRow(int viewportRow) {
        return viewportRow + emulator.getScrollbackSize() - scrollOffset;
    }

    /** Inverse of {@link #toAbsRow} — reprojects a stored absolute row onto the CURRENT
     *  viewport, using the live scrollOffset. Used only for painting; text extraction reads
     *  absolute rows directly via {@link TerminalEmulator#getCellAbs} instead. */
    private int fromAbsRow(int absRow) {
        return absRow - emulator.getScrollbackSize() + scrollOffset;
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
                TerminalCell cell = emulator.getCellAbs(r, c);
                // Skip the trailer half of a double-width glyph — it's a placeholder column,
                // not a character, so copying it would inject a spurious space.
                if (cell != null && cell.wideTrailer) continue;
                // appendCodePoint, not append: the field is an int code point, so append(int)
                // would write the numeric value instead of the character.
                line.appendCodePoint(cell != null && cell.character != '\0' ? cell.character : ' ');
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
        int c = cell.character;
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

        boolean multiline = text.contains("\n") || text.contains("\r");
        if (multiline) {
            text = confirmMultilinePaste(text);
            if (text == null) return;
            // The user may have edited it down to (or up from) a single line.
            multiline = text.contains("\n") || text.contains("\r");
        }

        // Clear selection on paste
        clearSelection();
        canvas.redraw();

        String sanitized = sanitizePaste(text);
        if (emulator.isBracketedPaste()) {
            // The program asked for bracketed paste, so it will treat everything between the
            // markers as pasted data rather than typing — no line splitting or pacing needed,
            // and the shell won't run anything until the user actually presses Enter.
            sendToServer(("\033[200~" + sanitized + "\033[201~").getBytes(StandardCharsets.UTF_8));
        } else if (multiline) {
            sendPastedLines(sanitized);
        } else {
            sendToServer(sanitized.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Sends a multi-line paste one line at a time, with a small delay between lines, instead
     *  of as one big burst. Some remote shells' line editors — bash/readline's backslash-line-
     *  continuation in particular — can misbehave when a multi-line command arrives all at once
     *  with none of the natural pacing of a human typing it, executing a continuation line
     *  prematurely. Runs on a background thread since it sleeps between lines; connection.send()
     *  is synchronized so this can't race with the UI thread's own key-typed sends. */
    private void sendPastedLines(String sanitized) {
        boolean trailingCr = sanitized.endsWith("\r");
        String body = trailingCr ? sanitized.substring(0, sanitized.length() - 1) : sanitized;
        String[] lines = body.split("\r", -1);
        Thread t = new Thread(() -> {
            try {
                for (int i = 0; i < lines.length; i++) {
                    if (!connection.isConnected()) return;
                    boolean lastLine = i == lines.length - 1;
                    String chunk = lines[i] + (!lastLine || trailingCr ? "\r" : "");
                    sendToServer(chunk.getBytes(StandardCharsets.UTF_8));
                    if (!lastLine) Thread.sleep(30);
                }
            } catch (InterruptedException ignored) {}
        }, "paste-lines");
        t.setDaemon(true);
        t.start();
    }

    /** Strip control bytes (especially ESC) from pasted text so a crafted clipboard can't
     *  inject terminal escape sequences into the shell; tab is kept. Line endings are
     *  normalized to a single '\r' per line — matching what the Enter key sends (see
     *  mapKey()) — so a Windows clipboard's CRLF doesn't submit each line twice (the \r
     *  submits it, then a passed-through \n would be a second, blank line-feed).
     *
     *  Dropping ESC is what makes bracketed paste safe: clipboard content cannot forge the
     *  ESC[201~ terminator to break out of the paste block and have the rest of itself run as
     *  typed commands. Keep that guarantee in mind before relaxing the filter. */
    private static String sanitizePaste(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r') {
                b.append('\r');
                if (i + 1 < s.length() && s.charAt(i + 1) == '\n') i++; // collapse CRLF
            } else if (c == '\n') {
                b.append('\r');
            } else if (c == '\t' || (c >= 0x20 && c != 0x7F)) {
                b.append(c);
            }
        }
        return b.toString();
    }

    /** @return the (possibly user-edited) text to paste, or null if the user cancelled. */
    private String confirmMultilinePaste(String text) {
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
        lbl.setText("The text to be pasted contains multiple lines. You can edit it below before pasting.");
        lbl.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Text editor = new Text(dlg, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
        editor.setText(text);
        editor.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Composite cmpBtns = new Composite(dlg, SWT.NONE);
        cmpBtns.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
        org.eclipse.swt.layout.RowLayout rl = new org.eclipse.swt.layout.RowLayout(SWT.HORIZONTAL);
        rl.spacing = 8;
        cmpBtns.setLayout(rl);
        Button btnPaste  = new Button(cmpBtns, SWT.PUSH); btnPaste.setText("Paste");
        Button btnCancel = new Button(cmpBtns, SWT.PUSH); btnCancel.setText("Cancel");
        dlg.setDefaultButton(btnPaste);

        String[] result = { null };
        btnPaste.addListener(SWT.Selection,  e -> { result[0] = editor.getText(); dlg.dispose(); });
        btnCancel.addListener(SWT.Selection, e -> { result[0] = null; dlg.dispose(); });
        dlg.addListener(SWT.Close,           e -> result[0] = null);

        dlg.open();
        Display d = dlg.getDisplay();
        while (!dlg.isDisposed()) { if (!d.readAndDispatch()) d.sleep(); }
        return result[0];
    }

    /** Pushes this tab's title (remote OSC title, or the app default) to the main window's title
     *  bar — but only if this tab is the one currently selected in the folder, so a title change
     *  on a background tab doesn't clobber what the user is actively looking at. Called whenever
     *  this tab's remote title changes, or (from {@link br.com.capoeirassh.ssh.ui.MainWindow})
     *  this tab just became the selected one. */
    void applyTitleIfActive() {
        if (tabItem.isDisposed()) return;
        if (tabItem.getParent().getSelection() != tabItem) return;
        setShellTitle(remoteTitle);
    }

    private void setShellTitle(String remote) {
        Shell shell = canvas.getShell();
        if (shell == null || shell.isDisposed()) return;
        if (remote != null && !remote.isBlank())
            shell.setText(remote);
        else
            applyBaseWindowTitle(shell);
    }

    /** Resets the shell title to the app default — used when a non-terminal tab (e.g. Sessions)
     *  becomes active, since it has no remote title of its own. */
    static void applyBaseWindowTitle(Shell shell) {
        if (shell == null || shell.isDisposed()) return;
        shell.setText("Capoeira SSH");
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
                // Traffic stopped — stay fixed on bold blue
                tabItem.setText(textBold);
                tabItem.setFont(getBoldTabFont());
                tabItem.setForeground(getColorActivityBlue());
                blinkRunning = false;
                return;
            }
            blinkPhase = !blinkPhase;
            if (blinkPhase) {
                tabItem.setText(textBold);
                tabItem.setFont(getBoldTabFont());
                tabItem.setForeground(getColorActivityBlue());
            } else {
                tabItem.setText(textNormal);
                tabItem.setFont(null);
                tabItem.setForeground(null);
            }
            scheduleBlink();
        });
    }

    /** Called from MainWindow when this tab becomes the active/focused tab. */
    public void clearActivity() {
        activityPending = false;
        blinkRunning    = false;
        display.asyncExec(() -> {
            if (tabItem.isDisposed()) return;
            if (disconnected) {
                // Re-assert the disconnected style: selecting the tab makes the
                // CTabFolder repaint it with its own selection colours, which
                // would otherwise wipe out the red indicator.
                tabItem.setText(textBold);
                tabItem.setFont(getBoldTabFont());
                tabItem.setForeground(getColorDisconnectedRed());
            } else {
                tabItem.setText(textNormal);
                tabItem.setFont(null);
                tabItem.setForeground(null);
            }
        });
    }

    private Font getBoldTabFont() {
        if (boldTabFont != null && !boldTabFont.isDisposed()) return boldTabFont;
        FontData[] fd = tabItem.getParent().getFont().getFontData();
        for (FontData d : fd) d.setStyle(SWT.BOLD);
        boldTabFont = new Font(display, fd);
        return boldTabFont;
    }

    /**
     * Builds the normal and bold tab label strings so both render at the same
     * pixel width — the normal one gets extra trailing spaces to compensate
     * for the narrower font, preventing the tab from growing/shrinking when
     * the font toggles between normal and bold during the activity blink.
     */
    private void computeTabTexts() {
        String base = "  " + tabTitle + "  ";
        textBold = base;
        GC gc = new GC(display);
        try {
            gc.setFont(tabItem.getParent().getFont());
            int normalWidth = gc.textExtent(base).x;
            gc.setFont(getBoldTabFont());
            int boldWidth = gc.textExtent(base).x;
            gc.setFont(tabItem.getParent().getFont());
            int spaceWidth = Math.max(1, gc.textExtent(" ").x);
            int deficit = boldWidth - normalWidth;
            int spaces = deficit > 0 ? (int) Math.ceil(deficit / (double) spaceWidth) : 0;
            textNormal = base + " ".repeat(spaces);
        } finally {
            gc.dispose();
        }
    }

    /** Renames the tab title, keeping the normal/bold width compensation in sync. */
    public void rename(String newTitle) {
        this.tabTitle = newTitle;
        computeTabTexts();
        if (tabItem.isDisposed()) return;
        boolean bold = tabItem.getFont() != null && tabItem.getFont().equals(boldTabFont);
        tabItem.setText(bold ? textBold : textNormal);
    }

    private Color getColorActivityBlue() {
        if (colorActivityBlue != null && !colorActivityBlue.isDisposed()) return colorActivityBlue;
        colorActivityBlue = new Color(display, 80, 160, 240);
        return colorActivityBlue;
    }

    private Color getColorDisconnectedRed() {
        if (colorDisconnectedRed != null && !colorDisconnectedRed.isDisposed()) return colorDisconnectedRed;
        colorDisconnectedRed = new Color(display, 220, 60, 60);
        return colorDisconnectedRed;
    }

    private void handleDisconnect() {
        disconnected = true;
        activityPending = false;
        blinkRunning    = false;
        display.asyncExec(() -> {
            if (tabItem.isDisposed()) return;
            tabItem.setText(textBold);
            tabItem.setFont(getBoldTabFont());
            tabItem.setForeground(getColorDisconnectedRed());
            canvas.redraw();
            if (onStateChanged != null) onStateChanged.run();
        });
    }

    // -----------------------------------------------------------------------
    // Reconnect
    // -----------------------------------------------------------------------
    public void reconnect(char[] password) {
        disconnected = false;
        startCursorBlink();
        display.asyncExec(() -> {
            if (!tabItem.isDisposed()) {
                tabItem.setText(textNormal);
                tabItem.setFont(null);
                tabItem.setForeground(null);
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

    /** Single place that assigns the default colours, so the packed RGB ints used by reverse
     *  video can never drift out of step with the Color objects used for drawing. Takes
     *  ownership of both Colors. */
    private void setDefaultColors(Color fg, Color bg) {
        defaultFg = fg;
        defaultBg = bg;
        RGB f = fg.getRGB(), b = bg.getRGB();
        defaultFgRgb = (f.red << 16) | (f.green << 8) | f.blue;
        defaultBgRgb = (b.red << 16) | (b.green << 8) | b.blue;
    }

    public int[] getAppearance() {
        // returns [fontSize, fgR, fgG, fgB, bgR, bgG, bgB]
        RGB fg = defaultFg.getRGB();
        RGB bg = defaultBg.getRGB();
        return new int[]{ termFontSize, fg.red, fg.green, fg.blue, bg.red, bg.green, bg.blue };
    }

    public String getFontName() { return termFontName; }

    public void applyAppearance(String newFontName, int newFontSize, org.eclipse.swt.graphics.RGB fg, org.eclipse.swt.graphics.RGB bg) {
        if (canvas.isDisposed()) return;
        termFontName = (newFontName != null && !newFontName.isBlank()) ? newFontName : MonoFonts.DEFAULT;
        termFontSize = newFontSize;
        if (!defaultBg.isDisposed()) defaultBg.dispose();
        if (!defaultFg.isDisposed()) defaultFg.dispose();
        setDefaultColors(new Color(display, fg), new Color(display, bg));
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
        setTracing(false);
        connection.close();
        display.asyncExec(() -> {
            disposeOffscreen();
            if (boldTabFont       != null && !boldTabFont.isDisposed())       boldTabFont.dispose();
            if (colorActivityBlue != null && !colorActivityBlue.isDisposed()) colorActivityBlue.dispose();
            if (colorDisconnectedRed != null && !colorDisconnectedRed.isDisposed()) colorDisconnectedRed.dispose();
            if (termFontBold != null && !termFontBold.isDisposed()) termFontBold.dispose();
            if (overlayFont  != null && !overlayFont.isDisposed())  overlayFont.dispose();
            if (glyphFallback != null) glyphFallback.dispose();
            if (resizeIndicator != null && !resizeIndicator.isDisposed()) resizeIndicator.dispose();
            if (copiedIndicator != null && !copiedIndicator.isDisposed()) copiedIndicator.dispose();
            if (traceDumpIndicator != null && !traceDumpIndicator.isDisposed()) traceDumpIndicator.dispose();
            if (colOverlayScrim != null && !colOverlayScrim.isDisposed()) colOverlayScrim.dispose();
            if (colOverlayText  != null && !colOverlayText.isDisposed())  colOverlayText.dispose();
            if (colReconnect    != null && !colReconnect.isDisposed())    colReconnect.dispose();
            if (colTraceBorder  != null && !colTraceBorder.isDisposed())  colTraceBorder.dispose();
            if (!termFont.isDisposed())  termFont.dispose();
            if (!defaultBg.isDisposed()) defaultBg.dispose();
            if (!defaultFg.isDisposed()) defaultFg.dispose();
        });
    }

    public CTabItem getTabItem()  { return tabItem;  }
    public Canvas   getCanvas()   { return canvas;   }
    public String   getTabTitle() { return tabTitle; }

    public boolean isLogging()   { return logStream != null; }

    /** Turns SSH protocol diagnostics on/off for the live connection immediately — takes effect
     *  on the current session, no reconnect needed. */
    public void setSshVerbose(boolean on) { connection.setVerbose(on); }

    public void setAllowColumnMode(boolean on) { emulator.setAllowColumnMode(on); }

    public String getLogDir() {
        SessionInfo s = sessionInfo;
        return (s.logDir != null && !s.logDir.isBlank())
               ? s.logDir
               : System.getProperty("user.home") + "/.capoeira/screen_captures";
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

    // -----------------------------------------------------------------------
    // Byte-level trace
    // -----------------------------------------------------------------------

    public boolean isTracing() { return trace != null; }

    /**
     * Turns byte-level tracing on or off for this tab, effective immediately on the live session.
     *
     * <p>Deliberately not persisted anywhere: a trace is a debugging aid for one incident, and a
     * tab that silently kept tracing across restarts would quietly fill the disk. Turning it on
     * always starts a fresh file.
     *
     * @return the path of the trace file when turning on (null if it could not be created), or
     *         the path just closed when turning off
     */
    public java.nio.file.Path setTracing(boolean on) {
        TerminalTrace current = trace;
        java.nio.file.Path result = null;
        if (on) {
            if (current != null) return current.getFile(); // already tracing
            TerminalTrace t = TerminalTrace.open(sessionInfo.label());
            if (t != null) {
                // Seed the file with the state as it stands before any traced byte arrives, so the
                // first RX record has a known starting point to be interpreted against.
                t.state(emulator.dumpState());
                result = t.getFile();
            }
            trace = t;
        } else {
            if (current != null) {
                result = current.getFile();
                current.close();
            }
            trace = null;
        }
        if (!canvas.isDisposed()) canvas.redraw();  // paint or clear the red trace border
        return result;
    }

    /** Writes a snapshot of the emulator's full state into the trace, in line with the surrounding
     *  byte records. No-op when not tracing — there is no file to write to. */
    public void dumpTraceState() {
        TerminalTrace t = trace;
        if (t == null) return;
        t.state(emulator.dumpState());
        showTraceDumpIndicator();
    }
}
