package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.model.SessionInfo;
import br.com.capoeirassh.ssh.terminal.TerminalEmulator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.DeviceData;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two related July 2026 security-audit findings (build 138/#24, build 139/#27), never given a
 * dedicated regression test:
 *
 * <ul>
 * <li>#24 — {@code render()} used to create-and-dispose a native bold {@code Font} per bold
 *     cell, per frame — a hostile server painting many bold cells could churn GDI font handles
 *     fast enough to freeze the whole UI thread. Fixed by caching one {@code termFontBold}
 *     instance, reused across every bold cell and every frame.</li>
 * <li>#27 — per-cell {@code Color} handles (foreground/background) created during rendering
 *     must be disposed every frame, not accumulate.</li>
 * </ul>
 *
 * <p>Uses SWT's own resource tracking ({@code DeviceData.tracking}), same mechanism as
 * {@link TerminalTabLeakTest} — renders many frames, each painting the whole grid bold with a
 * different colour, and asserts the live Color/Font handle count stays flat rather than growing
 * with the number of frames or cells rendered.
 */
class TerminalTabRenderHandleLeakTest {

    private static int[] countColorsAndFonts(Display display) {
        Object[] objects = display.getDeviceData().objects;
        int colors = 0, fonts = 0;
        for (Object o : objects) {
            if (o instanceof Color) colors++;
            else if (o instanceof Font) fonts++;
        }
        return new int[]{colors, fonts};
    }

    @Test
    @Timeout(60)
    void manyBoldFramesWithVaryingColors_neverGrowColorOrFontHandleCounts() throws Exception {
        ServerSocket fakeServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(() -> {
            while (!fakeServer.isClosed()) {
                try { Socket s = fakeServer.accept(); }
                catch (Exception e) { break; }
            }
        }, "fake-ssh-acceptor-renderleak-test");
        acceptor.setDaemon(true);
        acceptor.start();

        DeviceData data = new DeviceData();
        data.tracking = true;
        Display display = new Display(data);
        Shell shell = new Shell(display);
        try {
            shell.setSize(900, 600);
            CTabFolder folder = new CTabFolder(shell, SWT.NONE);
            folder.setSize(900, 600);
            SessionInfo info = new SessionInfo();
            info.name = "renderleak-test"; info.host = "127.0.0.1";
            info.port = fakeServer.getLocalPort(); info.username = "test";
            TerminalTab tab = new TerminalTab(folder, info, "pw".toCharArray());
            folder.setSelection(tab.getTabItem());
            shell.open();
            shell.layout(true, true);
            folder.layout(true, true);
            long deadline = System.currentTimeMillis() + 2_000;
            while (System.currentTimeMillis() < deadline) {
                if (!display.readAndDispatch()) Thread.sleep(10);
            }

            Method renderMethod = TerminalTab.class.getDeclaredMethod("render", GC.class);
            renderMethod.setAccessible(true);
            java.lang.reflect.Field emulatorField = TerminalTab.class.getDeclaredField("emulator");
            emulatorField.setAccessible(true);
            TerminalEmulator emu = (TerminalEmulator) emulatorField.get(tab);

            int cols = emu.getCols(), rows = emu.getRows();

            // One warm-up frame: absorb the one-time cost of creating termFontBold itself
            // (a legitimate single allocation, not a leak) before baselining.
            paintWholeGridBold(emu, cols, rows, 1);
            renderInvoke(renderMethod, tab, display);

            int[] before = countColorsAndFonts(display);

            // 30 frames, each with a different bold colour across the whole grid — this is
            // exactly the workload #24/#27 are about: many bold, many distinctly-coloured cells,
            // repeated over many frames.
            for (int frame = 2; frame <= 31; frame++) {
                paintWholeGridBold(emu, cols, rows, frame);
                renderInvoke(renderMethod, tab, display);
            }

            int[] after = countColorsAndFonts(display);

            assertEquals(before[1], after[1],
                    "Font handle count must not grow across frames — a per-cell/per-frame bold "
                  + "Font allocation (build-138 regression) would show up here as growth "
                  + "proportional to the number of frames rendered");
            // A generous slack, not zero-tolerance: legitimate per-frame Color allocation for
            // the CURRENT frame's palette is fine as long as it's disposed before the NEXT
            // frame — this only fails if the count keeps climbing frame over frame.
            assertTrue(after[0] <= before[0] + 5,
                    "Color handle count must not climb frame over frame (before=" + before[0]
                  + ", after 30 more frames=" + after[0] + ") — per-cell fg/bg Colors must be "
                  + "disposed every frame, not accumulate");

            tab.dispose();
            folder.dispose();
        } finally {
            if (!shell.isDisposed()) shell.dispose();
            if (!display.isDisposed()) display.dispose();
            fakeServer.close();
        }
    }

    private static void renderInvoke(Method renderMethod, TerminalTab tab, Display display) throws Exception {
        GC dummyScreen = new GC(display);
        try {
            renderMethod.invoke(tab, dummyScreen);
        } finally {
            dummyScreen.dispose();
        }
    }

    /** Fills the whole grid with bold cells, each frame using a different SGR 256-color index
     *  (38;5;N) so successive frames don't accidentally reuse the exact same Color instance. */
    private static void paintWholeGridBold(TerminalEmulator emu, int cols, int rows, int frame) {
        int colorIndex = 16 + (frame % 200); // stay within the 256-color cube, vary per frame
        StringBuilder sb = new StringBuilder();
        sb.append((char) 0x1B).append("[1;1H");
        sb.append((char) 0x1B).append("[1;38;5;").append(colorIndex).append('m');
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) sb.append('X');
        }
        emu.processBytes(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
    }
}
