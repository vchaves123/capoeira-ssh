package br.com.capoeirassh.ssh.storage;

import br.com.capoeirassh.ssh.model.SessionInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Best-effort import of SSH session definitions from other terminal clients.
 * Only host / port / username / display name are imported — never passwords,
 * even if the source tool stores one (PuTTY normally doesn't; MobaXterm's are
 * encrypted with a mechanism this app doesn't attempt to reverse).
 */
public final class SessionImporter {

    private SessionImporter() {}

    // -----------------------------------------------------------------------
    // PuTTY
    // -----------------------------------------------------------------------

    /** Reads PuTTY's saved sessions — Windows registry, or ~/.putty/sessions on Linux/macOS. */
    public static List<SessionInfo> fromPutty() {
        return isWindows() ? fromPuttyRegistry() : fromPuttyUnixFiles();
    }

    private static List<SessionInfo> fromPuttyRegistry() {
        List<SessionInfo> out = new ArrayList<>();
        String base = "HKCU\\Software\\SimonTatham\\PuTTY\\Sessions";
        String listing = runRegQuery(base);
        if (listing == null) return out;

        for (String line : listing.split("\\R")) {
            line = line.strip();
            String prefix = base.toUpperCase(Locale.ROOT).replace("HKCU", "HKEY_CURRENT_USER");
            if (!line.toUpperCase(Locale.ROOT).startsWith(prefix + "\\")) continue;
            String rawSubkey = line.substring(prefix.length() + 1);
            if (rawSubkey.isBlank()) continue;

            String detail = runRegQuery(base + "\\" + rawSubkey);
            if (detail == null) continue;
            Map<String, String> values = parseRegValues(detail);

            String protocol = values.getOrDefault("Protocol", "ssh");
            if (!protocol.equalsIgnoreCase("ssh")) continue; // skip telnet/serial/raw sessions

            String host = values.get("HostName");
            if (host == null || host.isBlank()) continue;

            SessionInfo s = new SessionInfo();
            s.name = puttyDecode(rawSubkey);
            s.host = host;
            s.port = parsePuttyPort(values.get("PortNumber"));
            s.username = values.getOrDefault("UserName", "");
            s.authType = SessionInfo.AuthType.PASSWORD;
            s.group = "Imported (PuTTY)";
            out.add(s);
        }
        return out;
    }

    private static List<SessionInfo> fromPuttyUnixFiles() {
        List<SessionInfo> out = new ArrayList<>();
        Path dir = Path.of(System.getProperty("user.home"), ".putty", "sessions");
        if (!Files.isDirectory(dir)) return out;

        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                Map<String, String> values = new HashMap<>();
                try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        int eq = line.indexOf('=');
                        if (eq > 0) values.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                    }
                } catch (IOException ignored) { continue; }

                String protocol = values.getOrDefault("Protocol", "ssh");
                if (!protocol.equalsIgnoreCase("ssh")) continue;
                String host = values.get("HostName");
                if (host == null || host.isBlank()) continue;

                SessionInfo s = new SessionInfo();
                s.name = puttyDecode(f.getFileName().toString());
                s.host = host;
                s.port = parsePuttyPort(values.get("PortNumber"));
                s.username = values.getOrDefault("UserName", "");
                s.authType = SessionInfo.AuthType.PASSWORD;
                s.group = "Imported (PuTTY)";
                out.add(s);
            }
        } catch (IOException ignored) {}
        return out;
    }

    private static int parsePuttyPort(String raw) {
        if (raw == null || raw.isBlank()) return 22;
        try {
            return raw.startsWith("0x") ? Integer.decode(raw) : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) { return 22; }
    }

    /** PuTTY escapes non-alphanumeric bytes in registry key / file names as %XX hex. */
    private static String puttyDecode(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                try {
                    sb.append((char) Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException ignored) {}
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static Map<String, String> parseRegValues(String regOutput) {
        Map<String, String> values = new HashMap<>();
        Pattern p = Pattern.compile("^(\\S+)\\s+REG_\\S+\\s+(.*)$");
        for (String line : regOutput.split("\\R")) {
            Matcher m = p.matcher(line.strip());
            if (m.matches()) values.put(m.group(1), m.group(2).strip());
        }
        return values;
    }

    private static String runRegQuery(String key) {
        try {
            Process p = new ProcessBuilder("reg", "query", key).redirectErrorStream(false).start();
            String out;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = r.lines().reduce("", (a, b) -> a + b + "\n");
            }
            p.waitFor();
            return p.exitValue() == 0 ? out : null;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // MobaXterm
    // -----------------------------------------------------------------------

    /**
     * Best-effort parse of a MobaXterm .ini file's [Bookmarks] section. MobaXterm's
     * session-string encoding is not officially documented and has changed across
     * versions; this recognises the common "#109#0%host%port%username%..." SSH
     * bookmark format and silently skips anything it can't confidently parse.
     */
    public static List<SessionInfo> fromMobaXtermIni(Path iniFile) {
        List<SessionInfo> out = new ArrayList<>();
        if (iniFile == null || !Files.isRegularFile(iniFile)) return out;

        String content;
        try { content = readTextAutoDetect(iniFile); }
        catch (IOException e) { return out; }

        // MobaXterm puts each subfolder's bookmarks in its own numbered section
        // ([Bookmarks], [Bookmarks_1], [Bookmarks_2], ...); SubRep names the subfolder.
        boolean inBookmarks = false;
        String subfolder = "";
        for (String rawLine : content.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty()) continue;
            if (line.startsWith("[")) {
                inBookmarks = line.toLowerCase(Locale.ROOT).startsWith("[bookmarks");
                subfolder = "";
                continue;
            }
            if (!inBookmarks) continue;

            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String name  = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (name.equalsIgnoreCase("ImgNum")) continue;
            if (name.equalsIgnoreCase("SubRep")) { subfolder = value; continue; }

            // SSH bookmarks start with "#109#" (MobaXterm's internal session-type code).
            if (!value.startsWith("#109#")) continue;
            String[] fields = value.split("%");
            // fields[0] = "#109#0"; host/port/username follow at fixed positions.
            if (fields.length < 4) continue;

            SessionInfo s = new SessionInfo();
            s.name     = name;
            s.host     = fields[1].trim();
            s.port     = parseIntSafe(fields[2].trim(), 22);
            s.username = fields[3].trim();
            if (s.host.isBlank()) continue;
            s.authType = SessionInfo.AuthType.PASSWORD;
            s.group    = subfolder.isBlank() ? "Imported (MobaXterm)" : "Imported (MobaXterm) - " + subfolder;
            out.add(s);
        }
        return out;
    }

    /**
     * Reads a text file whose encoding isn't known in advance: honours a UTF-8/UTF-16
     * BOM if present, otherwise uses UTF-8 only if the bytes are valid UTF-8, falling
     * back to Windows-1252 (the common encoding for legacy Windows .ini files) so
     * structural ASCII delimiters ('[', '=', '%') are never misread.
     */
    /** Real MobaXterm.ini files are at most a few hundred KB; this is a generous cap that
     *  still rejects a corrupted/oversized/crafted file before it's read fully into memory —
     *  Files.readAllBytes() + the String conversions below have no size limit of their own,
     *  and an OutOfMemoryError isn't caught by the caller's catch(IOException). */
    private static final long MAX_IMPORT_FILE_BYTES = 20L * 1024 * 1024;

    private static String readTextAutoDetect(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_IMPORT_FILE_BYTES)
            throw new IOException("File is too large to be a MobaXterm.ini (" + size + " bytes) — refusing to import.");
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes));
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (java.nio.charset.CharacterCodingException notUtf8) {
            return new String(bytes, java.nio.charset.Charset.forName("windows-1252"));
        }
    }

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
