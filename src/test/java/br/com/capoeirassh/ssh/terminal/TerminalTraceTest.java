package br.com.capoeirassh.ssh.terminal;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TerminalTrace}, the byte-level session trace.
 *
 * <p>A trace is only worth writing if it can be read back unambiguously afterwards: every record
 * on its own line, the direction distinguishable, the bytes recoverable verbatim, and the relative
 * order of RX/TX/STATE preserved. Those four properties are what is asserted here.
 *
 * <p>Like the other storage tests, this relies on surefire redirecting {@code user.home} to
 * {@code target/test-home} (see pom.xml) so a test run never writes into the real
 * {@code ~/.capoeira/traces}.
 */
class TerminalTraceTest {

    @BeforeAll
    static void verifyHomeIsRedirected() {
        String home = System.getProperty("user.home");
        Assumptions.assumeTrue(home != null && home.contains("test-home"),
                "user.home was not redirected to a test directory (got: " + home + ") — "
              + "refusing to run tests that write into the real ~/.capoeira. "
              + "Run via `mvn test` (surefire applies the redirect).");
    }

    /** Opens a trace, hands it to the caller, closes it, and returns the lines it wrote. */
    private static List<String> traceLines(java.util.function.Consumer<TerminalTrace> body) throws Exception {
        TerminalTrace t = TerminalTrace.open("unit-test");
        assertNotNull(t, "trace file could not be created");
        Path file = t.getFile();
        try {
            body.accept(t);
        } finally {
            t.close();
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        Files.deleteIfExists(file);
        return lines;
    }

    @Test
    @DisplayName("a new trace starts with an OPEN record")
    void newTrace_startsWithOpenRecord() throws Exception {
        List<String> lines = traceLines(t -> { });
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains(" OPEN "), "got: " + lines.get(0));
    }

    @Test
    @DisplayName("received bytes are recorded as RX with their length and verbatim hex")
    void receivedBytes_recordedAsHex() throws Exception {
        byte[] data = { 0x1B, '[', 'A' };
        List<String> lines = traceLines(t -> t.rx(data, 0, data.length));

        String rx = lines.get(1);
        assertTrue(rx.contains(" RX "), "record must be tagged RX: " + rx);
        assertTrue(rx.endsWith(" 3 1b5b41"),
                "record must carry the byte count then lowercase hex: " + rx);
    }

    @Test
    @DisplayName("sent bytes are recorded as TX")
    void sentBytes_recordedAsTx() throws Exception {
        List<String> lines = traceLines(t -> t.tx(new byte[] { 0x0D }));

        String tx = lines.get(1);
        assertTrue(tx.contains(" TX "), "record must be tagged TX: " + tx);
        assertTrue(tx.endsWith(" 1 0d"), "got: " + tx);
    }

    @Test
    @DisplayName("only the requested slice of the buffer is recorded")
    void rxRespectsOffsetAndLength() throws Exception {
        // The SSH reader reuses one 4 KB buffer and only the first n bytes are real — recording
        // the whole array would fill the trace with stale bytes from the previous read.
        byte[] buf = { 'a', 'b', 'c', 'd' };
        List<String> lines = traceLines(t -> t.rx(buf, 1, 2));

        assertTrue(lines.get(1).endsWith(" 2 6263"),
                "only bytes at index 1..2 ('b','c') should be recorded, got: " + lines.get(1));
    }

    @Test
    @DisplayName("high bytes are zero-padded so every byte is exactly two hex digits")
    void highBytes_arePaddedToTwoDigits() throws Exception {
        byte[] data = { 0x00, 0x0F, (byte) 0xFF, (byte) 0x80 };
        List<String> lines = traceLines(t -> t.rx(data, 0, data.length));

        assertTrue(lines.get(1).endsWith(" 4 000fff80"),
                "unpadded hex would make the stream impossible to split back into bytes: " + lines.get(1));
    }

    @Test
    @DisplayName("a state snapshot is recorded as a STATE record on its own line")
    void stateSnapshot_recordedAsOwnRecord() throws Exception {
        String json = new TerminalEmulator(10, 2).dumpState();
        List<String> lines = traceLines(t -> t.state(json));

        String state = lines.get(1);
        assertTrue(state.contains(" STATE "), "got: " + state);
        assertTrue(state.endsWith(json), "the snapshot must be recorded verbatim: " + state);
        assertEquals(2, lines.size(), "a snapshot must occupy exactly one line");
    }

    @Test
    @DisplayName("records keep the order they were written in, across all three kinds")
    void records_keepChronologicalOrder() throws Exception {
        // This ordering is the entire point of putting RX, TX and STATE in one file: an analysis
        // has to be able to say "these bytes arrived, then the screen looked like this".
        List<String> lines = traceLines(t -> {
            t.rx(new byte[] { 'a' }, 0, 1);
            t.tx(new byte[] { 'b' });
            t.state("{}");
            t.rx(new byte[] { 'c' }, 0, 1);
        });

        assertEquals(5, lines.size());
        assertTrue(lines.get(1).contains(" RX "));
        assertTrue(lines.get(2).contains(" TX "));
        assertTrue(lines.get(3).contains(" STATE "));
        assertTrue(lines.get(4).contains(" RX "));
    }

    @Test
    @DisplayName("empty and null writes produce no record")
    void emptyWrites_produceNoRecord() throws Exception {
        List<String> lines = traceLines(t -> {
            t.rx(new byte[0], 0, 0);
            t.rx(null, 0, 5);
            t.tx(new byte[0]);
            t.tx(null);
        });
        assertEquals(1, lines.size(), "only the OPEN record should be present");
    }

    @Test
    @DisplayName("writing after close is silently ignored rather than throwing")
    void writeAfterClose_isIgnored() throws Exception {
        TerminalTrace t = TerminalTrace.open("unit-test-closed");
        assertNotNull(t);
        Path file = t.getFile();
        t.close();

        // A trace is a debugging aid; a late write from the SSH reader thread racing the user
        // turning tracing off must never take the session down.
        assertDoesNotThrow(() -> {
            t.rx(new byte[] { 'x' }, 0, 1);
            t.tx(new byte[] { 'y' });
            t.state("{}");
            t.close();
        });

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(1, lines.size(), "nothing should have been appended after close");
        Files.deleteIfExists(file);
    }

    @Test
    @DisplayName("the trace file lands under ~/.capoeira/traces with a .trace extension")
    void traceFile_landsInExpectedDirectory() throws Exception {
        TerminalTrace t = TerminalTrace.open("my session/name");
        assertNotNull(t);
        Path file = t.getFile();
        t.close();

        assertTrue(file.toString().replace('\\', '/').contains("/.capoeira/traces/"),
                "unexpected location: " + file);
        assertTrue(file.getFileName().toString().endsWith(".trace"), "unexpected name: " + file);
        // The session label reaches the file name, so a path separator in it must not escape the
        // traces directory.
        assertFalse(file.getFileName().toString().contains("/"));
        assertFalse(file.getFileName().toString().contains("\\"));

        Files.deleteIfExists(file);
    }

    @Test
    @DisplayName("two traces opened in the same second get distinct files")
    void concurrentTraces_doNotShareAFile() throws Exception {
        TerminalTrace a = TerminalTrace.open("dup");
        TerminalTrace b = TerminalTrace.open("dup");
        assertNotNull(a);
        assertNotNull(b);
        try {
            assertNotEquals(a.getFile(), b.getFile(),
                    "a second trace must not append into the first one's file");
        } finally {
            a.close();
            b.close();
            Files.deleteIfExists(a.getFile());
            Files.deleteIfExists(b.getFile());
        }
    }
}
