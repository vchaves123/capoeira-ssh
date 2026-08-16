package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.model.SessionInfo;
import br.com.capoeirassh.ssh.terminal.TerminalEmulator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code TerminalTab.getFullHistoryText()} — feeds the "Save history to file..." link on the
 * disconnected overlay (issue #100). Had no test of its own; writes directly to the private
 * {@code emulator} field via reflection (mirroring {@code TerminalTabStripAnsiTest}'s harness),
 * since there's no public way to push bytes into a tab's buffer outside a live SSH read loop.
 *
 * <p>Reuses one {@code TerminalTab} across all tests (constructing one needs a fake TCP acceptor
 * and a real SWT Display — expensive to redo per test) and clears the visible screen with an
 * ANSI "clear + home" sequence in {@code @BeforeEach} instead, so each test starts from a known
 * blank state without depending on execution order.
 */
@Tag("ui") // needs a real SWT Display — no display on the Linux/macOS CI runners
class TerminalTabHistoryTest {

    private static ServerSocket fakeServer;
    private static Thread acceptor;
    private static Display display;
    private static Shell shell;
    private static CTabFolder folder;
    private static TerminalTab tab;
    private static TerminalEmulator emulator;

    @BeforeAll
    static void setUpOneSharedTab() throws Exception {
        fakeServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        acceptor = new Thread(() -> {
            while (!fakeServer.isClosed()) {
                try { Socket s = fakeServer.accept(); /* accept and hold, never send a banner */ }
                catch (Exception e) { break; }
            }
        }, "fake-ssh-acceptor-history-test");
        acceptor.setDaemon(true);
        acceptor.start();

        display = new Display();
        shell = new Shell(display);
        folder = new CTabFolder(shell, SWT.NONE);
        SessionInfo info = new SessionInfo();
        info.name = "history-test"; info.host = "127.0.0.1";
        info.port = fakeServer.getLocalPort(); info.username = "test";
        tab = new TerminalTab(folder, info, "pw".toCharArray());

        Field f = TerminalTab.class.getDeclaredField("emulator");
        f.setAccessible(true);
        emulator = (TerminalEmulator) f.get(tab);
    }

    @AfterAll
    static void tearDown() {
        if (tab != null) tab.dispose();
        if (folder != null && !folder.isDisposed()) folder.dispose();
        if (shell != null && !shell.isDisposed()) shell.dispose();
        if (display != null && !display.isDisposed()) display.dispose();
        if (fakeServer != null) { try { fakeServer.close(); } catch (Exception ignored) {} }
    }

    @BeforeEach
    void resetScreen() {
        // ESC[2J (erase entire screen) + ESC[H (cursor home) — leaves scrollback alone, but
        // nothing in these tests writes enough lines to push anything into it anyway.
        emulator.processBytes("[2J[H".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @Timeout(15)
    void freshlyCleared_historyIsAllBlankLines() {
        String[] lines = tab.getFullHistoryText().split("\n", -1);
        for (int i = 0; i < lines.length - 1; i++) { // last element is the trailing-\n artifact
            assertEquals("", lines[i], "a freshly cleared screen should have no visible content on any line");
        }
    }

    @Test
    @Timeout(15)
    void writtenText_appearsOnItsLine() {
        emulator.processBytes("hello world".getBytes(StandardCharsets.UTF_8));
        String[] lines = tab.getFullHistoryText().split("\n", -1);
        assertEquals("hello world", lines[0]);
    }

    @Test
    @Timeout(15)
    void trailingBlankColumns_areNotRenderedAsSpaces() {
        emulator.processBytes("hi".getBytes(StandardCharsets.UTF_8));
        String[] lines = tab.getFullHistoryText().split("\n", -1);
        assertEquals("hi", lines[0], "the other 78 blank columns on this 80-col row must not appear as trailing spaces");
    }

    @Test
    @Timeout(15)
    void carriageReturnLineFeed_movesWrittenTextToTheNextLine() {
        emulator.processBytes("line1\r\nline2".getBytes(StandardCharsets.UTF_8));
        String[] lines = tab.getFullHistoryText().split("\n", -1);
        assertEquals("line1", lines[0]);
        assertEquals("line2", lines[1]);
    }

    @Test
    @Timeout(15)
    void totalLineCount_matchesScrollbackPlusVisibleRows() {
        int expectedRows = emulator.getScrollbackSize() + emulator.getRows();
        // getFullHistoryText() appends '\n' after every row including the last, so splitting
        // with limit -1 yields one extra empty trailing element.
        String[] lines = tab.getFullHistoryText().split("\n", -1);
        assertEquals(expectedRows + 1, lines.length);
    }
}
