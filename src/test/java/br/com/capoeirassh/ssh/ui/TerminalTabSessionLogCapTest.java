package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.model.SessionInfo;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code TerminalTab}'s session-log writer caps the log file at 100 MB ({@code MAX_LOG_BYTES})
 * so a hostile/misbehaving SSH server streaming endless output can't fill the user's disk. Never
 * had a test — actually writing 100 MB to exercise it for real would be slow and wasteful, so
 * this drives {@code logBytesWritten} to just under the cap via reflection instead, then confirms
 * a write that would cross it is skipped entirely (not partially written) and closes the log.
 *
 * <p>Reuses the same fake-TCP-acceptor + real {@code TerminalTab} harness as
 * {@link TerminalTabStripAnsiTest} (see its javadoc for why). No production code is modified;
 * only private fields/methods are reached via reflection to drive the scenario.
 */
@Tag("ui") // needs a real SWT Display — no display on the Linux/macOS CI runners
class TerminalTabSessionLogCapTest {

    private static ServerSocket fakeServer;
    private static Thread acceptor;
    private static Display display;
    private static Shell shell;
    private static CTabFolder folder;
    private static TerminalTab tab;
    private static Method writeLog;
    private static Field logStreamField;
    private static Field logBytesWrittenField;
    private static Field maxLogBytesField;

    @BeforeAll
    static void setUpOneSharedTab() throws Exception {
        fakeServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        acceptor = new Thread(() -> {
            while (!fakeServer.isClosed()) {
                try { fakeServer.accept(); }
                catch (Exception e) { break; }
            }
        }, "fake-ssh-acceptor-logcap-test");
        acceptor.setDaemon(true);
        acceptor.start();

        display = new Display();
        shell = new Shell(display);
        folder = new CTabFolder(shell, SWT.NONE);
        SessionInfo info = new SessionInfo();
        info.name = "logcap-test"; info.host = "127.0.0.1";
        info.port = fakeServer.getLocalPort(); info.username = "test";
        tab = new TerminalTab(folder, info, "pw".toCharArray());

        writeLog = TerminalTab.class.getDeclaredMethod("writeLog", byte[].class, int.class);
        writeLog.setAccessible(true);
        logStreamField = TerminalTab.class.getDeclaredField("logStream");
        logStreamField.setAccessible(true);
        logBytesWrittenField = TerminalTab.class.getDeclaredField("logBytesWritten");
        logBytesWrittenField.setAccessible(true);
        maxLogBytesField = TerminalTab.class.getDeclaredField("MAX_LOG_BYTES");
        maxLogBytesField.setAccessible(true);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (tab != null) tab.dispose();
        if (folder != null && !folder.isDisposed()) folder.dispose();
        if (shell != null && !shell.isDisposed()) shell.dispose();
        if (display != null && !display.isDisposed()) display.dispose();
        if (fakeServer != null) fakeServer.close();
    }

    @Test
    @Timeout(15)
    void writeLog_belowCap_writesNormally() throws Exception {
        ByteArrayOutputStream fake = new ByteArrayOutputStream();
        logStreamField.set(tab, fake);
        logBytesWrittenField.set(tab, 0L);

        byte[] data = "hello".getBytes(StandardCharsets.US_ASCII);
        writeLog.invoke(tab, data, data.length);

        assertEquals("hello", fake.toString(StandardCharsets.US_ASCII));
        assertNotNull(logStreamField.get(tab), "log must remain open when well under the cap");
        assertEquals(5L, (long) logBytesWrittenField.get(tab));
    }

    @Test
    @Timeout(15)
    void writeLog_crossingTheCap_skipsTheWriteEntirely_andClosesTheLog() throws Exception {
        long maxLogBytes = (long) maxLogBytesField.get(null);
        ByteArrayOutputStream fake = new ByteArrayOutputStream();
        logStreamField.set(tab, fake);
        logBytesWrittenField.set(tab, maxLogBytes - 3); // 3 bytes of headroom left

        byte[] data = "this is way more than 3 bytes".getBytes(StandardCharsets.US_ASCII);
        writeLog.invoke(tab, data, data.length);

        assertEquals(0, fake.size(),
                "a write that would cross the cap must be skipped ENTIRELY, not partially written "
              + "up to the remaining headroom");
        assertNull(logStreamField.get(tab), "crossing the cap must close the log stream");
    }

    @Test
    @Timeout(15)
    void writeLog_afterLogWasClosed_isANoOp_doesNotThrow() throws Exception {
        logStreamField.set(tab, null); // simulate an already-closed log (e.g. cap already hit)
        byte[] data = "more data".getBytes(StandardCharsets.US_ASCII);
        assertDoesNotThrow(() -> writeLog.invoke(tab, data, data.length));
    }
}
