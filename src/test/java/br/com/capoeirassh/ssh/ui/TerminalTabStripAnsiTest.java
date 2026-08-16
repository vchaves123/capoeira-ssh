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

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code TerminalTab.stripAnsi()} — the stateful filter that removes escape/control sequences
 * from what gets written to the session log file — had zero test coverage. The August 2026
 * external-input-validation review explicitly declined to touch this method further ("regression
 * risk" — it's a hand-rolled state machine parsing byte-at-a-time across {@code read()} chunk
 * boundaries), so this test ONLY calls it via reflection; it does not modify {@code stripAnsi()}
 * or any other production code.
 *
 * <p>Specifically covers build 41's fix: a 3-byte ESC sequence with an intermediate byte (e.g.
 * {@code ESC # B}, DEC line attributes) used to only consume the intermediate byte, leaving the
 * final byte to fall through and appear as a stray character in the log.
 *
 * <p>Reuses {@link TerminalTabLeakTest}'s fake-TCP-acceptor harness (a real {@code TerminalTab}
 * is required since {@code stripAnsi} is a stateful instance method, not static) — see that
 * class's javadoc for why a fake acceptor is used instead of just an unreachable port.
 */
@Tag("ui") // needs a real SWT Display — no display on the Linux/macOS CI runners
class TerminalTabStripAnsiTest {

    private static ServerSocket fakeServer;
    private static Thread acceptor;
    private static Display display;
    private static Shell shell;
    private static CTabFolder folder;
    private static TerminalTab tab;
    private static Method stripAnsi;

    @BeforeAll
    static void setUpOneSharedTab() throws Exception {
        fakeServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        acceptor = new Thread(() -> {
            while (!fakeServer.isClosed()) {
                try { fakeServer.accept(); /* accept and hold, never send a banner */ }
                catch (Exception e) { break; }
            }
        }, "fake-ssh-acceptor-stripansi-test");
        acceptor.setDaemon(true);
        acceptor.start();

        display = new Display();
        shell = new Shell(display);
        folder = new CTabFolder(shell, SWT.NONE);
        SessionInfo info = new SessionInfo();
        info.name = "stripansi-test"; info.host = "127.0.0.1";
        info.port = fakeServer.getLocalPort(); info.username = "test";
        tab = new TerminalTab(folder, info, "pw".toCharArray());

        stripAnsi = TerminalTab.class.getDeclaredMethod("stripAnsi", byte[].class, int.class);
        stripAnsi.setAccessible(true);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (tab != null) tab.dispose();
        if (folder != null && !folder.isDisposed()) folder.dispose();
        if (shell != null && !shell.isDisposed()) shell.dispose();
        if (display != null && !display.isDisposed()) display.dispose();
        if (fakeServer != null) fakeServer.close();
    }

    private static String strip(String input) throws Exception {
        byte[] in = input.getBytes(StandardCharsets.ISO_8859_1);
        byte[] out = (byte[]) stripAnsi.invoke(tab, in, in.length);
        return new String(out, StandardCharsets.ISO_8859_1);
    }

    private static final String ESC = "";

    @Test
    @Timeout(15)
    void plainText_passesThroughUnchanged() throws Exception {
        assertEquals("hello world", strip("hello world"));
    }

    @Test
    @Timeout(15)
    void threeByteEscSequence_isFullyConsumed_noStrayFinalByte() throws Exception {
        // The actual build-41 bug: ESC # B (intermediate '#' + final 'B') used to leak the 'B'.
        assertEquals("beforeafter", strip("before" + ESC + "#Bafter"));
    }

    @Test
    @Timeout(15)
    void anotherThreeByteEscSequence_charsetDesignator_isFullyConsumed() throws Exception {
        assertEquals("beforeafter", strip("before" + ESC + "(Bafter"));
    }

    @Test
    @Timeout(15)
    void twoByteEscSequence_isFullyConsumed() throws Exception {
        assertEquals("beforeafter", strip("before" + ESC + "7after")); // ESC 7 = save cursor
    }

    @Test
    @Timeout(15)
    void csiSequence_isFullyConsumed_finalByteNotLeaked() throws Exception {
        assertEquals("beforeafter", strip("before" + ESC + "[31mafter"));
    }

    @Test
    @Timeout(15)
    void oscSequence_terminatedByBel_isFullyConsumed() throws Exception {
        assertEquals("beforeafter", strip("before" + ESC + "]0;title" + (char) 0x07 + "after"));
    }

    @Test
    @Timeout(15)
    void crLfAndTab_arePreserved() throws Exception {
        assertEquals("a\r\nb\tc", strip("a\r\nb\tc"));
    }

    @Test
    @Timeout(15)
    void otherC0Controls_areStripped() throws Exception {
        assertEquals("ac", strip("a" + (char) 0x01 + (char) 0x02 + "c"));
    }

    @Test
    @Timeout(15)
    void multiByteUtf8Character_isPreservedAcrossTheStateMachine() throws Exception {
        String withAccent = "café"; // "café"
        byte[] utf8 = withAccent.getBytes(StandardCharsets.UTF_8);
        byte[] out = (byte[]) stripAnsi.invoke(tab, utf8, utf8.length);
        assertArrayEquals(utf8, out, "a valid multi-byte UTF-8 sequence must pass through untouched");
    }

    @Test
    @Timeout(15)
    void ansiStateSurvivesAcrossCalls_splitEscSequenceAcrossTwoChunks() throws Exception {
        // Exercises the persisted ansiState field: an ESC sequence split across two separate
        // read() chunks (exactly how it arrives from the network in practice) must still be
        // fully consumed, not leak its second half as text once it starts in a later call.
        String first  = strip("before" + ESC);
        String second = strip("#Bafter");
        assertEquals("before", first);
        assertEquals("after", second, "the intermediate+final bytes arriving in the NEXT chunk must "
              + "still be consumed by the parser state left over from the previous call");
    }

    // -----------------------------------------------------------------------
    // sanitizeVerboseLine — static, so no TerminalTab instance/reflection needed
    // beyond visibility. Guards against a malicious/MITM SSH server injecting
    // escape sequences via the sshVerbose (JSch handshake log) diagnostics feature.
    // -----------------------------------------------------------------------

    private static String sanitizeVerboseLine(String line) throws Exception {
        Method m = TerminalTab.class.getDeclaredMethod("sanitizeVerboseLine", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, line);
    }

    @Test
    void sanitizeVerboseLine_plainText_passesThrough() throws Exception {
        assertEquals("Connecting to host", sanitizeVerboseLine("Connecting to host"));
    }

    @Test
    void sanitizeVerboseLine_stripsEscapeByte() throws Exception {
        String result = sanitizeVerboseLine("before" + ESC + "[31mafter");
        assertFalse(result.contains(ESC), "an ESC byte must never survive into the terminal-embedded log line");
    }

    @Test
    void sanitizeVerboseLine_replacesCrLfWithASpace_soOneLineCannotFakeMultiple() throws Exception {
        String result = sanitizeVerboseLine("line1\r\nline2\nline3");
        assertFalse(result.contains("\n"));
        assertFalse(result.contains("\r"));
        // \r and \n are each individually replaced by their own space (not collapsed as a pair),
        // so "\r\n" becomes two spaces.
        assertEquals("line1  line2 line3", result);
    }

    @Test
    void sanitizeVerboseLine_stripsC0AndC1ControlRanges() throws Exception {
        String result = sanitizeVerboseLine("a" + (char) 0x01 + (char) 0x1F + "b" + (char) 0x7F + (char) 0x9F + "c");
        assertEquals("abc", result);
    }

    @Test
    void sanitizeVerboseLine_nullInput_returnsEmptyString_doesNotThrow() throws Exception {
        assertEquals("", sanitizeVerboseLine(null));
    }
}
