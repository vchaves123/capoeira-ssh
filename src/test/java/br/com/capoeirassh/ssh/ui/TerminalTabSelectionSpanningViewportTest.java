package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.model.SessionInfo;
import br.com.capoeirassh.ssh.terminal.TerminalEmulator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * User-reported bug: scroll up into history, drag-select downward across many screens, and the
 * moment the selection grows to span the whole visible area the highlight vanishes — even though
 * the selection itself is still intact (right-click still copies exactly the right text).
 *
 * <p>The selection is stored in ABSOLUTE buffer rows and reprojected onto the viewport for
 * painting via {@code fromAbsRow()}, which legitimately returns a NEGATIVE row once the selection's
 * start has scrolled above the top of the visible area. {@code render()} initializes its
 * {@code selRow0} to {@code -1} to mean "no selection at all" and then gates the whole per-cell
 * highlight on {@code selRow0 >= 0} — so a negative-but-valid reprojected row is indistinguishable
 * from "nothing is selected", and the highlight is skipped for every cell on screen.
 *
 * <p>These tests assert on actual painted pixels rather than on internal state, because the
 * internal state was never wrong — only the painting was, which is exactly why the bug survived
 * three earlier fix attempts aimed at the mouse-event handlers.
 *
 * <p>A blank cell gives an unambiguous signal: selection swaps fg/bg, so a selected blank cell is
 * filled with the default FOREGROUND colour, while an unselected one keeps the default background.
 */
@Tag("ui") // needs a real SWT Display — no display on the Linux/macOS CI runners
class TerminalTabSelectionSpanningViewportTest {

    private static ServerSocket fakeServer;
    private static Display display;
    private static Shell shell;
    private static CTabFolder folder;
    private static TerminalTab tab;
    private static TerminalEmulator emu;

    private static int charWidth, charHeight, cols, rows;
    private static RGB defaultFg, defaultBg;

    private static Field scrollOffsetField, offscreenBufferField;
    private static Field selAnchorColField, selAnchorRowField, selEndColField, selEndRowField;
    private static Method renderMethod;

    @BeforeAll
    static void setUpOneSharedTab() throws Exception {
        fakeServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(() -> {
            while (!fakeServer.isClosed()) {
                try { fakeServer.accept(); } catch (Exception e) { break; }
            }
        }, "fake-ssh-acceptor-selection-spanning-test");
        acceptor.setDaemon(true);
        acceptor.start();

        display = new Display();
        shell = new Shell(display);
        shell.setSize(900, 600);
        folder = new CTabFolder(shell, SWT.NONE);
        folder.setSize(900, 600);
        SessionInfo info = new SessionInfo();
        info.name = "selection-spanning-test"; info.host = "127.0.0.1";
        info.port = fakeServer.getLocalPort(); info.username = "test";
        tab = new TerminalTab(folder, info, "pw".toCharArray());
        folder.setSelection(tab.getTabItem()); // canvas only gets a nonzero size once selected
        shell.open();
        shell.layout(true, true);
        folder.layout(true, true);

        // Let the debounced initial resize-to-fit settle before reading cols/rows.
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            if (!display.readAndDispatch()) Thread.sleep(10);
        }

        Field emulatorField = TerminalTab.class.getDeclaredField("emulator");
        emulatorField.setAccessible(true);
        emu = (TerminalEmulator) emulatorField.get(tab);

        // Plenty of scrollback, so a selection can start well above the viewport and end well
        // below it while both endpoints stay inside the buffer.
        for (int i = 0; i < 400; i++) {
            emu.processBytes(("line " + i + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        }

        charWidth  = (int) readField("charWidth");
        charHeight = (int) readField("charHeight");
        int fgRgb  = (int) readField("defaultFgRgb");
        int bgRgb  = (int) readField("defaultBgRgb");
        defaultFg  = new RGB((fgRgb >> 16) & 0xFF, (fgRgb >> 8) & 0xFF, fgRgb & 0xFF);
        defaultBg  = new RGB((bgRgb >> 16) & 0xFF, (bgRgb >> 8) & 0xFF, bgRgb & 0xFF);
        cols = emu.getCols();
        rows = emu.getRows();

        scrollOffsetField    = field("scrollOffset");
        offscreenBufferField = field("offscreenBuffer");
        selAnchorColField    = field("selAnchorCol");
        selAnchorRowField    = field("selAnchorRow");
        selEndColField       = field("selEndCol");
        selEndRowField       = field("selEndRow");

        renderMethod = TerminalTab.class.getDeclaredMethod("render", GC.class);
        renderMethod.setAccessible(true);
    }

    private static Field field(String name) throws Exception {
        Field f = TerminalTab.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static Object readField(String name) throws Exception {
        return field(name).get(tab);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (tab != null) tab.dispose();
        if (folder != null && !folder.isDisposed()) folder.dispose();
        if (shell != null && !shell.isDisposed()) shell.dispose();
        if (display != null && !display.isDisposed()) display.dispose();
        if (fakeServer != null) fakeServer.close();
    }

    @BeforeEach
    void resetViewAndSelection() throws Exception {
        // The fake server never completes an SSH handshake, so the tab eventually flips to
        // "disconnected" and render() lays a semi-transparent scrim over the whole canvas —
        // which blends every sampled pixel and has nothing to do with what these tests measure.
        // How soon that happens depends on how long the JVM has been up, so leaving it alone
        // makes these tests pass alone and fail in a full-suite run. Pin it off instead.
        field("disconnected").setBoolean(tab, false);
        scrollOffsetField.setInt(tab, 0);
        selAnchorColField.setInt(tab, -1);
        selAnchorRowField.setInt(tab, -1);
        selEndColField.setInt(tab, -1);
        selEndRowField.setInt(tab, -1);
    }

    /** Renders into the offscreen buffer and returns its pixels. */
    private static ImageData renderAndCapture() throws Exception {
        GC screen = new GC(tab.getCanvas());
        try {
            renderMethod.invoke(tab, screen);
        } finally {
            screen.dispose();
        }
        Image offscreen = (Image) offscreenBufferField.get(tab);
        assertNotNull(offscreen, "render() must have created the offscreen buffer");
        return offscreen.getImageData();
    }

    /** Background colour actually painted at the centre of the given cell. */
    private static RGB cellBackground(ImageData data, int row, int col) {
        int px = col * charWidth + charWidth / 2;
        int py = row * charHeight + charHeight / 2;
        assertTrue(px < data.width && py < data.height,
                "test setup: cell (" + row + "," + col + ") maps to pixel (" + px + "," + py
              + ") outside the " + data.width + "x" + data.height + " buffer");
        return data.palette.getRGB(data.getPixel(px, py));
    }

    /** A column guaranteed to be blank on every row ("line NNN" is far shorter than the width). */
    private static int blankColumn() { return cols - 1; }

    @Test
    @Timeout(20)
    @DisplayName("control: a selection wholly inside the viewport paints its highlight")
    void selectionInsideViewport_isPainted() throws Exception {
        int histSize = emu.getScrollbackSize();
        scrollOffsetField.setInt(tab, 100);
        int viewportTopAbs = histSize - 100;

        // Rows 2..4 of the visible area, full width.
        selAnchorRowField.setInt(tab, viewportTopAbs + 2);
        selAnchorColField.setInt(tab, 0);
        selEndRowField.setInt(tab, viewportTopAbs + 4);
        selEndColField.setInt(tab, cols - 1);

        ImageData data = renderAndCapture();

        assertEquals(defaultFg, cellBackground(data, 3, blankColumn()),
                "a blank cell inside the selection must be filled with the default FOREGROUND "
              + "colour (selection swaps fg/bg) — if this fails the harness itself is wrong, not "
              + "the code under test");
        assertEquals(defaultBg, cellBackground(data, 0, blankColumn()),
                "a row above the selection must keep the default background");
        assertEquals(defaultBg, cellBackground(data, rows - 1, blankColumn()),
                "a row below the selection must keep the default background");
    }

    @Test
    @Timeout(20)
    @DisplayName("a selection spanning the entire viewport paints EVERY visible row highlighted")
    void selectionSpanningWholeViewport_paintsEveryRow() throws Exception {
        int histSize = emu.getScrollbackSize();
        scrollOffsetField.setInt(tab, 100);
        int viewportTopAbs = histSize - 100;

        // The reported scenario: the selection starts well ABOVE the visible area and ends well
        // BELOW it, so the viewport sits entirely inside the selected range. Reprojected onto the
        // viewport these become row -27 and row rows+20 respectively; the negative start is what
        // collides with render()'s "-1 means no selection" sentinel.
        int anchorAbs = viewportTopAbs - 27;
        int endAbs    = viewportTopAbs + rows + 20;
        assertTrue(anchorAbs >= 0, "test setup: need enough scrollback above the viewport");
        assertTrue(endAbs < histSize + rows, "test setup: selection end must stay inside the buffer");

        selAnchorRowField.setInt(tab, anchorAbs);
        selAnchorColField.setInt(tab, 0);
        selEndRowField.setInt(tab, endAbs);
        selEndColField.setInt(tab, cols - 1);

        ImageData data = renderAndCapture();

        // Every single visible row is inside the selection, so every one must be highlighted.
        for (int r = 0; r < rows; r++) {
            assertEquals(defaultFg, cellBackground(data, r, blankColumn()),
                    "row " + r + " of " + rows + " lies inside the selection (absolute rows "
                  + anchorAbs + ".." + endAbs + ", viewport starts at " + viewportTopAbs + ") and "
                  + "must be painted highlighted. The selection is intact — right-click still "
                  + "copies the correct text — but render() gates the highlight on `selRow0 >= 0`, "
                  + "and fromAbsRow() legitimately returns a negative row once the selection's "
                  + "start scrolls above the viewport, which is indistinguishable from the -1 "
                  + "sentinel meaning \"no selection\".");
        }
    }

    @Test
    @Timeout(20)
    @DisplayName("a selection starting above the viewport and ending inside it highlights only down to its end")
    void selectionStartingAboveViewport_highlightsUpToItsEnd() throws Exception {
        int histSize = emu.getScrollbackSize();
        scrollOffsetField.setInt(tab, 100);
        int viewportTopAbs = histSize - 100;

        // Starts above the viewport (negative reprojected row) but ends on visible row 5, so the
        // highlight must cover rows 0..5 and stop — proving the fix keeps the END boundary honest
        // instead of just force-selecting everything whenever the start is off-screen.
        int anchorAbs = viewportTopAbs - 10;
        int endAbs    = viewportTopAbs + 5;

        selAnchorRowField.setInt(tab, anchorAbs);
        selAnchorColField.setInt(tab, 0);
        selEndRowField.setInt(tab, endAbs);
        selEndColField.setInt(tab, cols - 1);

        ImageData data = renderAndCapture();

        assertEquals(defaultFg, cellBackground(data, 0, blankColumn()),
                "visible row 0 is inside the selection and must be highlighted");
        assertEquals(defaultFg, cellBackground(data, 5, blankColumn()),
                "visible row 5 is the selection's last row and must be highlighted");
        assertEquals(defaultBg, cellBackground(data, 6, blankColumn()),
                "visible row 6 is past the selection's end and must NOT be highlighted");
        assertEquals(defaultBg, cellBackground(data, rows - 1, blankColumn()),
                "the last visible row is far past the selection's end and must NOT be highlighted");
    }

    @Test
    @Timeout(20)
    @DisplayName("no selection at all paints no highlight anywhere")
    void noSelection_paintsNoHighlight() throws Exception {
        scrollOffsetField.setInt(tab, 100);

        ImageData data = renderAndCapture();

        for (int r = 0; r < rows; r++) {
            assertEquals(defaultBg, cellBackground(data, r, blankColumn()),
                    "row " + r + " must keep the default background when nothing is selected");
        }
    }
}
