package br.com.capoeirassh.ssh.ssh;

import br.com.capoeirassh.ssh.storage.SecureFiles;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class PacketTraceLogger {

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter LINE_TS  = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final OutputStream out;

    public PacketTraceLogger(String host) throws IOException {
        Path dir = Path.of(System.getProperty("user.home"), ".capoeira", "trace");
        SecureFiles.createDirectories(dir);
        String ts   = LocalDateTime.now().format(FILE_TS);
        String base = (host == null || host.isBlank()) ? "session" : host.replaceAll("[^\\w\\-.]", "_");
        Path file = dir.resolve(ts + "_" + base + ".trace.log");
        this.out = SecureFiles.openAppend(file);
    }

    public synchronized void logRx(byte[] buf, int len) { log("RX", buf, 0, len); }
    public synchronized void logTx(byte[] buf)          { log("TX", buf, 0, buf.length); }

    private void log(String direction, byte[] buf, int off, int len) {
        try {
            StringBuilder sb = new StringBuilder(len * 4 + 64);
            sb.append('[').append(LocalDateTime.now().format(LINE_TS)).append("] ")
              .append(direction).append(' ').append(len).append(" bytes:\n");
            appendHexDump(sb, buf, off, len);
            sb.append('\n');
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {}
    }

    private static void appendHexDump(StringBuilder sb, byte[] buf, int off, int len) {
        for (int row = 0; row < len; row += 16) {
            int rowLen = Math.min(16, len - row);
            sb.append(String.format("  %06x  ", row));
            for (int i = 0; i < 16; i++) {
                if (i < rowLen) sb.append(String.format("%02x ", buf[off + row + i]));
                else            sb.append("   ");
                if (i == 7) sb.append(' ');
            }
            sb.append(" |");
            for (int i = 0; i < rowLen; i++) {
                int b = buf[off + row + i] & 0xFF;
                sb.append((b >= 0x20 && b < 0x7F) ? (char) b : '.');
            }
            sb.append("|\n");
        }
    }

    public synchronized void close() {
        try { out.close(); } catch (IOException ignored) {}
    }
}
