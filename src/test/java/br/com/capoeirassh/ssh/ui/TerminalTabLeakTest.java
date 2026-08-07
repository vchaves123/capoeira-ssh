package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.model.SessionInfo;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.DeviceData;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opens and closes 200 simulated {@link TerminalTab}s (no real SSH server) and reports concrete
 * SWT resource (Color/Font) and JVM thread counts before/after, using SWT's own built-in resource
 * tracking ({@link DeviceData#tracking}) — the same mechanism the SWT "Sleak" leak-detection tool
 * uses — rather than instrumenting TerminalTab itself.
 *
 * <p><b>Why a fake TCP acceptor instead of just an unreachable host/port:</b> TerminalTab's
 * constructor unconditionally spawns a background thread that calls {@code SshConnection.connect}
 * (15s connect timeout, hardcoded in production code). If that connect failed instantly (e.g.
 * ECONNREFUSED against a closed localhost port), there would be a genuine race between this test
 * calling {@code tab.dispose()} (which sets the private {@code closed} flag checked by the
 * background thread's catch block) and that background thread reaching its own failure path —
 * and losing that race means {@code TerminalTab.runSsh()} opens a real, modal "Connection error"
 * {@code MessageBox}, which would hang this test forever waiting for a user click that never
 * comes. Instead, a local socket accepts the TCP connection but never sends an SSH version
 * banner, so JSch blocks for its full ~15s connect timeout before failing — by which time every
 * tab in this test has already been disposed (the whole 200-tab loop takes well under a second),
 * so {@code closed} is guaranteed true long before any background thread could reach its catch
 * block. No production code is modified.
 *
 * <p><b>Why there is a warm-up tab before the "before" snapshot:</b> the first time
 * {@code GC.setFont(...)} is used anywhere in the process, SWT's Win32 backend
 * ({@code DefaultSWTFontRegistry}) lazily creates and caches one internal {@code Font} wrapper
 * per distinct (name, size, style) it is asked for — by design, disposed only when the whole
 * {@code Device} is torn down, never per-call. Likewise, the first {@code java.awt.Font} touched
 * anywhere (used by {@link GlyphFallback} as a glyph-coverage oracle) lazily starts the JVM's
 * "Java2D Disposer" thread, once, for the process's lifetime. Both are one-time framework
 * initialization costs, not something that scales with the number of tabs — measuring them
 * without first warming up those paths would count normal JVM/SWT startup as a false "leak".
 * Creating and disposing one throwaway tab (and waiting out its background connection attempt)
 * before taking the baseline warms up both paths, so the real 200-tab measurement below isolates
 * only what {@link TerminalTab} itself allocates per tab.
 */
@Tag("ui") // needs a real SWT Display — no display on the Linux/macOS CI runners
class TerminalTabLeakTest {

    private static int[] countColorsAndFonts(Display display) {
        Object[] objects = display.getDeviceData().objects;
        int colors = 0, fonts = 0;
        for (Object o : objects) {
            if (o instanceof Color) colors++;
            else if (o instanceof Font) fonts++;
        }
        return new int[]{colors, fonts};
    }

    private static long countSshThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("ssh-"))
                .count();
    }

    @Test
    @Timeout(90)
    void open200SimulatedTabsAndClose_reportsColorFontAndThreadCounts() throws Exception {
        java.util.Set<Thread> threadsBeforeSet;
        ServerSocket fakeServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(() -> {
            while (!fakeServer.isClosed()) {
                try {
                    Socket s = fakeServer.accept();
                    // Accept and hold — deliberately never send the SSH version banner.
                } catch (Exception e) {
                    break;
                }
            }
        }, "fake-ssh-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();

        DeviceData data = new DeviceData();
        data.tracking = true;
        Display display = new Display(data);
        Shell shell = new Shell(display);
        try {
            int port = fakeServer.getLocalPort();

            // --- Warm-up: absorb SWT's/AWT's one-time framework init cost before baselining ---
            CTabFolder warmupFolder = new CTabFolder(shell, SWT.NONE);
            SessionInfo warmupInfo = new SessionInfo();
            warmupInfo.name = "warmup"; warmupInfo.host = "127.0.0.1"; warmupInfo.port = port; warmupInfo.username = "test";
            TerminalTab warmupTab = new TerminalTab(warmupFolder, warmupInfo, "pw".toCharArray());
            warmupTab.dispose();
            long warmupPumpDeadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < warmupPumpDeadline) {
                if (!display.readAndDispatch()) Thread.sleep(10);
            }
            warmupFolder.dispose();
            // Wait out the warm-up tab's own background connection attempt so its "ssh-*" thread
            // doesn't pollute the "before" thread snapshot below.
            long warmupThreadDeadline = System.currentTimeMillis() + 20_000;
            while (countSshThreads() > 0 && System.currentTimeMillis() < warmupThreadDeadline) {
                Thread.sleep(200);
            }

            int[] before = countColorsAndFonts(display);
            long threadsBefore = Thread.getAllStackTraces().size();
            threadsBeforeSet = new java.util.HashSet<>(Thread.getAllStackTraces().keySet());

            CTabFolder folder = new CTabFolder(shell, SWT.NONE);
            List<TerminalTab> tabs = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                SessionInfo info = new SessionInfo();
                info.name     = "sim-tab-" + i;
                info.host     = "127.0.0.1";
                info.port     = port;
                info.username = "test";
                TerminalTab tab = new TerminalTab(folder, info, "pw".toCharArray());
                tabs.add(tab);
                tab.dispose(); // sets `closed` immediately — see class javadoc for why this is safe
            }

            // Pump the event loop so every asyncExec-queued disposal (TerminalTab.dispose()
            // wraps its Color/Font teardown in display.asyncExec) actually runs.
            long pumpDeadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < pumpDeadline) {
                if (!display.readAndDispatch()) Thread.sleep(10);
            }

            folder.dispose();
            int[] after = countColorsAndFonts(display);

            // Diagnostic: print the exact allocation stack trace of every still-undisposed
            // Color/Font, using SWT's own tracking (DeviceData#errors mirrors DeviceData#objects
            // index-for-index with the Error captured at the moment each Resource was created).
            DeviceData afterData = display.getDeviceData();
            for (int i = 0; i < afterData.objects.length; i++) {
                if (afterData.objects[i] instanceof Font || afterData.objects[i] instanceof Color) {
                    System.out.println("--- leaked " + afterData.objects[i].getClass().getSimpleName()
                            + " allocated at: ---");
                    afterData.errors[i].printStackTrace(System.out);
                }
            }

            // The 200 "ssh-*" background threads are each blocked in JSch's connect() until its
            // ~15s timeout fires, then return immediately (closed==true, no MessageBox, no
            // handleDisconnect()). Wait for them to actually finish before counting threads.
            long threadDeadline = System.currentTimeMillis() + 30_000;
            while (countSshThreads() > 0 && System.currentTimeMillis() < threadDeadline) {
                Thread.sleep(200);
            }
            long threadsAfter = Thread.getAllStackTraces().size();
            long sshThreadsStillAlive = countSshThreads();

            // Diagnostic: name+stack of any thread present after that wasn't before.
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (!threadsBeforeSet.contains(t)) {
                    System.out.println("--- leaked thread: \"" + t.getName() + "\" (daemon=" + t.isDaemon()
                            + ", state=" + t.getState() + ") ---");
                    for (StackTraceElement el : t.getStackTrace()) System.out.println("    at " + el);
                }
            }

            System.out.println("=== TerminalTab leak test: 200 open/close cycles ===");
            System.out.println("Color objects : before=" + before[0] + "  after=" + after[0]
                    + "  leaked=" + (after[0] - before[0]));
            System.out.println("Font objects  : before=" + before[1] + "  after=" + after[1]
                    + "  leaked=" + (after[1] - before[1]));
            System.out.println("JVM threads   : before=" + threadsBefore + "  after=" + threadsAfter
                    + "  leaked=" + (threadsAfter - threadsBefore)
                    + "  (ssh-* still alive: " + sshThreadsStillAlive + ")");

            assertEquals(0, sshThreadsStillAlive, "ssh-* background threads still alive after the wait deadline");
            assertEquals(before[0], after[0], "Color objects leaked: " + (after[0] - before[0]));
            assertEquals(before[1], after[1], "Font objects leaked: " + (after[1] - before[1]));
            assertEquals(threadsBefore, threadsAfter, "JVM threads leaked: " + (threadsAfter - threadsBefore));
        } finally {
            try { fakeServer.close(); } catch (Exception ignored) {}
            if (!shell.isDisposed()) shell.dispose();
            if (!display.isDisposed()) display.dispose();
        }
    }
}
