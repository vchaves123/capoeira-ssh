package br.com.capoeirassh.ssh.terminal;

import br.com.capoeirassh.ssh.storage.SecureFiles;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Raw byte-level trace of one terminal session, for debugging emulator behavior.
 *
 * <p>Unlike the session log (see {@code TerminalTab.writeLog}), which records only received bytes
 * with ANSI escapes stripped out, a trace records <em>everything</em> verbatim as hex: bytes
 * received from the server (RX), bytes sent to it (TX), and — on user request — a full snapshot of
 * the emulator's internal state (STATE). All three share one file in strict chronological order,
 * so an analysis can line up "these bytes arrived" against "the screen then looked like this".
 *
 * <p>Line format, one record per line:
 * <pre>
 *   2026-08-13T14:22:31.123456700 RX   45 1b5b303b376d48656c6c6f0d0a
 *   2026-08-13T14:22:31.987654300 TX    3 1b5b41
 *   2026-08-13T14:22:32.111222300 STATE   {"rows":24,...}
 * </pre>
 * The timestamp is {@link LocalDateTime#now()} at nanosecond resolution — note that the underlying
 * clock's actual granularity is coarser than a nanosecond, so two records can carry the same
 * stamp; file order, not the stamp, is authoritative for sequencing.
 *
 * <p>Tracing is a debugging aid the user turns on per tab and is never persisted — a new tab always
 * starts untraced. Writes are serialized on this object because RX arrives on the SSH reader thread
 * while TX and STATE originate on the SWT UI thread.
 */
public final class TerminalTrace implements AutoCloseable {

    /** Trace files hold hex, so they grow ~2x faster than the raw stream; cap higher than the
     *  100 MB session log but still bounded, so a forgotten trace can't fill the disk. */
    public static final long MAX_TRACE_BYTES = 500L * 1024 * 1024;

    private static final DateTimeFormatter TS  = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.nnnnnnnnn");
    private static final AtomicInteger     SEQ = new AtomicInteger();
    private static final char[]            HEX = "0123456789abcdef".toCharArray();

    private final Path         file;
    private OutputStream       out;
    private long               bytesWritten;

    private TerminalTrace(Path file, OutputStream out) {
        this.file = file;
        this.out  = out;
    }

    /**
     * Open a new trace file under {@code ~/.capoeira/traces}.
     *
     * @param sessionName used in the file name; sanitized to word characters
     * @return the open trace, or {@code null} if the file could not be created (tracing then stays
     *         off rather than taking the session down over a debugging aid)
     */
    public static TerminalTrace open(String sessionName) {
        try {
            Path dir = Path.of(System.getProperty("user.home"), ".capoeira", "traces");
            SecureFiles.createDirectories(dir);

            String base = (sessionName == null || sessionName.isBlank())
                          ? "session" : sessionName.replaceAll("[^\\w\\-.]", "_");
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

            Path f = dir.resolve(stamp + "_" + base + ".trace");
            if (Files.exists(f)) f = dir.resolve(stamp + "_" + base + "_" + SEQ.incrementAndGet() + ".trace");

            TerminalTrace t = new TerminalTrace(f, SecureFiles.openAppend(f));
            t.writeLine("OPEN", "trace started for " + base);
            return t;
        } catch (IOException e) {
            return null;
        }
    }

    /** Path of the file being written, for showing the user where the trace landed. */
    public Path getFile() { return file; }

    /** Record bytes received from the server. */
    public void rx(byte[] data, int offset, int length) { writeBytes("RX", data, offset, length); }

    /** Record bytes sent to the server. */
    public void tx(byte[] data) {
        if (data != null) writeBytes("TX", data, 0, data.length);
    }

    /** Record a snapshot of emulator state, produced by {@link TerminalEmulator#dumpState()}. */
    public void state(String json) { writeLine("STATE", json); }

    private void writeBytes(String kind, byte[] data, int offset, int length) {
        if (data == null || length <= 0) return;
        StringBuilder sb = new StringBuilder(length * 2 + 8);
        sb.append(length).append(' ');
        for (int i = offset; i < offset + length; i++) {
            int b = data[i] & 0xFF;
            sb.append(HEX[b >>> 4]).append(HEX[b & 0x0F]);
        }
        writeLine(kind, sb.toString());
    }

    private synchronized void writeLine(String kind, String payload) {
        if (out == null) return;
        String line = LocalDateTime.now().format(TS) + " " + kind + " " + payload + "\n";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        // Stop (rather than rotate) at the cap, matching the session log's behavior: a trace is
        // read start-to-end for a specific incident, so silently dropping the beginning would be
        // worse than stopping once the incident is presumably long past.
        if (bytesWritten + bytes.length > MAX_TRACE_BYTES) { close(); return; }
        try {
            out.write(bytes);
            out.flush();
            bytesWritten += bytes.length;
        } catch (IOException e) {
            out = null; // give up quietly — a broken trace must not disturb the session
        }
    }

    @Override
    public synchronized void close() {
        if (out == null) return;
        try { out.close(); } catch (IOException ignored) { }
        out = null;
    }
}
