package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.model.SessionInfo;
import br.com.capoeirassh.ssh.terminal.TerminalEmulator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Build-30 sibling bug: {@code TerminalTab.render()} used to recompute the column count from
 * {@code area.width / charWidth}, which could differ by 1 from {@code emulator.getCols()} due to
 * the 80ms resize debounce (a program drawing at the new width immediately, before the canvas's
 * reported width had caught up) — the rightmost column (e.g. the "5" in "2025" in MC's date
 * column) went invisible, never actually rendered. The fix reads {@code emulator.getCols()}/
 * {@code getRows()} directly as the loop bound, with no recomputation from {@code area.width} at
 * all — this test writes a distinctive background colour into all four grid corners and confirms
 * every one of them actually gets painted, i.e. the render loop truly reaches column
 * {@code cols-1} and row {@code rows-1}, not silently stopping one short.
 *
 * <p>Reuses the same fake-TCP-acceptor + real {@code TerminalTab} harness as
 * {@link TerminalTabStripAnsiTest}. No production code is modified.
 */
class TerminalTabRenderBoundsTest {

    private static ServerSocket fakeServer;
    private static Thread acceptor;
    private static Display display;
    private static Shell shell;
    private static CTabFolder folder;
    private static TerminalTab tab;
    private static Field emulatorField;
    private static Field offscreenBufferField;
    private static Field charWidthField;
    private static Field charHeightField;
    private static Method renderMethod;

    private static final String ESC = "";

    @BeforeAll
    static void setUpOneSharedTab() throws Exception {
        fakeServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        acceptor = new Thread(() -> {
            while (!fakeServer.isClosed()) {
                try { Socket s = fakeServer.accept(); }
                catch (Exception e) { break; }
            }
        }, "fake-ssh-acceptor-renderbounds-test");
        acceptor.setDaemon(true);
        acceptor.start();

        display = new Display();
        shell = new Shell(display);
        shell.setSize(900, 600);
        folder = new CTabFolder(shell, SWT.NONE);
        folder.setSize(900, 600);
        SessionInfo info = new SessionInfo();
        info.name = "renderbounds-test"; info.host = "127.0.0.1";
        info.port = fakeServer.getLocalPort(); info.username = "test";
        tab = new TerminalTab(folder, info, "pw".toCharArray());
        folder.setSelection(tab.getTabItem()); // the canvas only gets laid out to a nonzero
                                                // size once its CTabItem is the selected one
        shell.open();
        shell.layout(true, true);
        folder.layout(true, true);

        // The initial resize-to-fit is itself debounced (see TerminalTab's own 80ms comment) —
        // pump the event loop for a bit so that settles BEFORE this test starts writing to
        // specific columns, or emulator.getCols() could still change out from under it.
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            if (!display.readAndDispatch()) Thread.sleep(10);
        }

        emulatorField = TerminalTab.class.getDeclaredField("emulator");
        emulatorField.setAccessible(true);
        offscreenBufferField = TerminalTab.class.getDeclaredField("offscreenBuffer");
        offscreenBufferField.setAccessible(true);
        charWidthField = TerminalTab.class.getDeclaredField("charWidth");
        charWidthField.setAccessible(true);
        charHeightField = TerminalTab.class.getDeclaredField("charHeight");
        charHeightField.setAccessible(true);
        renderMethod = TerminalTab.class.getDeclaredMethod("render", GC.class);
        renderMethod.setAccessible(true);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (tab != null) tab.dispose();
        if (folder != null && !folder.isDisposed()) folder.dispose();
        if (shell != null && !shell.isDisposed()) shell.dispose();
        if (display != null && !display.isDisposed()) display.dispose();
        if (fakeServer != null) fakeServer.close();
    }

    private static void send(TerminalEmulator emu, String seq) {
        emu.processBytes(seq.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    @Timeout(15)
    void render_paintsAllFourGridCorners_notJustCols1Rows1() throws Exception {
        TerminalEmulator emu = (TerminalEmulator) emulatorField.get(tab);
        int cols = emu.getCols();
        int rows = emu.getRows();
        assertTrue(cols > 1 && rows > 1, "test setup: need a real multi-cell grid");

        // Paint a distinctive background (SGR 44 = blue) into all four corners. A space
        // character (not a letter) so the cell is pure background with no glyph ink — sampling
        // the pixel dead-center of the cell must never land on a text stroke instead.
        send(emu, ESC + "[H" + ESC + "[44m ");                              // (0,0)
        send(emu, ESC + "[1;" + cols + "H" + ESC + "[44m ");                 // (0, cols-1)
        send(emu, ESC + "[" + rows + ";1H" + ESC + "[44m ");                // (rows-1, 0)
        send(emu, ESC + "[" + rows + ";" + cols + "H" + ESC + "[44m ");     // (rows-1, cols-1)
        // Move the cursor away from every tested corner — the cursor highlight (when its blink
        // phase is on) swaps a cell's colours, which would make this assertion flaky depending
        // on unrelated timer state, not what this test is about.
        send(emu, ESC + "[2;2H");

        // Force a fresh render into the offscreen buffer.
        GC dummyScreen = new GC(display);
        try {
            renderMethod.invoke(tab, dummyScreen);
        } finally {
            dummyScreen.dispose();
        }

        Image offscreen = (Image) offscreenBufferField.get(tab);
        assertNotNull(offscreen, "render() must have created the offscreen buffer");
        int charWidth  = (int) charWidthField.get(tab);
        int charHeight = (int) charHeightField.get(tab);
        ImageData imageData = offscreen.getImageData();

        assertCornerIsBlue(imageData, 0, 0, charWidth, charHeight, "(0,0)");
        assertCornerIsBlue(imageData, cols - 1, 0, charWidth, charHeight, "(0,cols-1) — the actual build-30 bug spot");
        assertCornerIsBlue(imageData, 0, rows - 1, charWidth, charHeight, "(rows-1,0)");
        assertCornerIsBlue(imageData, cols - 1, rows - 1, charWidth, charHeight, "(rows-1,cols-1)");
    }

    private static void assertCornerIsBlue(ImageData imageData, int col, int row, int charWidth, int charHeight, String label) {
        int px = col * charWidth + charWidth / 2;
        int py = row * charHeight + charHeight / 2;
        assertTrue(px < imageData.width && py < imageData.height,
                "test setup: pixel (" + px + "," + py + ") for corner " + label + " must be within the "
              + imageData.width + "x" + imageData.height + " offscreen buffer");
        int pixelValue = imageData.getPixel(px, py);
        PaletteData palette = imageData.palette;
        RGB rgb = palette.getRGB(pixelValue);
        // SGR 44 = ANSI blue, palette index 4 -> RGB(0,0,128) per TerminalEmulator's buildPalette().
        assertEquals(new RGB(0, 0, 128), rgb,
                "corner " + label + " must be painted with the blue background actually written there "
              + "— if this is still the default background colour, the render loop stopped one short "
              + "of the real grid bounds (the exact build-30 symptom: MC's rightmost date-column "
              + "character never being drawn)");
    }
}
