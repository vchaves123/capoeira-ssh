package br.com.quatorzebis.ssh.storage;

import br.com.quatorzebis.ssh.model.SessionInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Persists sessions as *.session Properties files under $HOME/.14bis/.
 * Groups map to subdirectories.
 *
 *   ~/.14bis/
 *   ├── {uuid}.session          ← root-level session
 *   └── production/
 *       └── {uuid}.session      ← session inside group "production"
 */
public final class SessionStorage {

    private static final Path BASE = Path.of(System.getProperty("user.home"), ".14bis", "sessions");
    private static final String EXT = ".session";

    private SessionStorage() {}

    // -----------------------------------------------------------------------
    // Save / Delete
    // -----------------------------------------------------------------------

    public static void save(SessionInfo s) throws IOException {
        Path dir = dir(s.group);
        Files.createDirectories(dir);

        Properties p = new Properties();
        p.setProperty("id",       s.id);
        p.setProperty("name",     s.name);
        p.setProperty("host",     s.host);
        p.setProperty("port",     String.valueOf(s.port));
        p.setProperty("username", s.username);
        p.setProperty("authType", s.authType.name());
        p.setProperty("keyPath",      s.keyPath      != null ? s.keyPath      : "");
        p.setProperty("group",        s.group        != null ? s.group        : "");
        p.setProperty("credentialId", s.credentialId != null ? s.credentialId : "");
        p.setProperty("appearFontSize", String.valueOf(s.appearFontSize));
        p.setProperty("appearFg", s.appearFgR + "," + s.appearFgG + "," + s.appearFgB);
        p.setProperty("appearBg", s.appearBgR + "," + s.appearBgG + "," + s.appearBgB);
        p.setProperty("logEnabled",  String.valueOf(s.logEnabled));
        p.setProperty("logDir",      s.logDir      != null ? s.logDir      : "");
        p.setProperty("logFileName", s.logFileName  != null ? s.logFileName : "");

        try (OutputStream out = Files.newOutputStream(dir.resolve(s.fileName()))) {
            p.store(out, "14bis SSH session");
        }
    }

    public static void delete(SessionInfo s) throws IOException {
        Files.deleteIfExists(dir(s.group).resolve(s.fileName()));
    }

    // -----------------------------------------------------------------------
    // Load
    // -----------------------------------------------------------------------

    public static List<SessionInfo> loadAll() {
        List<SessionInfo> list = new ArrayList<>();
        if (!Files.exists(BASE)) return list;

        // Root-level sessions
        safeList(BASE)
            .filter(p -> p.getFileName().toString().endsWith(EXT))
            .forEach(p -> load(p, "").ifPresent(list::add));

        // Group sessions
        safeList(BASE)
            .filter(Files::isDirectory)
            .sorted(Comparator.comparing(p -> p.getFileName().toString()))
            .forEach(groupDir -> {
                String group = groupDir.getFileName().toString();
                safeList(groupDir)
                    .filter(p -> p.getFileName().toString().endsWith(EXT))
                    .forEach(p -> load(p, group).ifPresent(list::add));
            });

        return list;
    }

    public static List<String> loadGroups() {
        if (!Files.exists(BASE)) return List.of();
        return safeList(BASE)
            .filter(Files::isDirectory)
            .map(p -> p.getFileName().toString())
            .sorted()
            .collect(Collectors.toList());
    }

    // -----------------------------------------------------------------------
    // Group management
    // -----------------------------------------------------------------------

    public static void createGroup(String groupName) throws IOException {
        if (groupName == null || groupName.isBlank()) return;
        Files.createDirectories(BASE.resolve(sanitize(groupName)));
    }

    public static void deleteGroup(String groupName) throws IOException {
        Path groupDir = BASE.resolve(sanitize(groupName));
        if (!Files.exists(groupDir)) return;
        // Only delete if empty (no sessions left)
        try (Stream<Path> s = Files.list(groupDir)) {
            if (s.findAny().isEmpty()) Files.delete(groupDir);
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private static Optional<SessionInfo> load(Path file, String group) {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            return Optional.empty();
        }
        SessionInfo s   = new SessionInfo();
        String fname    = file.getFileName().toString();
        s.id        = fname.endsWith(EXT) ? fname.substring(0, fname.length() - EXT.length()) : fname;
        s.name      = p.getProperty("name",     "");
        s.host      = p.getProperty("host",     "");
        s.port      = parsePort(p.getProperty("port", "22"));
        s.username  = p.getProperty("username", "");
        s.keyPath       = p.getProperty("keyPath",      "");
        s.group         = group;
        s.credentialId   = p.getProperty("credentialId", "");
        s.appearFontSize = parseInt(p.getProperty("appearFontSize", "0"));
        int[] fg = parseRgb(p.getProperty("appearFg", "204,204,204"));
        s.appearFgR = fg[0]; s.appearFgG = fg[1]; s.appearFgB = fg[2];
        int[] bg = parseRgb(p.getProperty("appearBg", "0,0,0"));
        s.appearBgR = bg[0]; s.appearBgG = bg[1]; s.appearBgB = bg[2];
        s.logEnabled  = Boolean.parseBoolean(p.getProperty("logEnabled", "false"));
        s.logDir      = p.getProperty("logDir",      "");
        s.logFileName = p.getProperty("logFileName", "");
        String at        = p.getProperty("authType", "PASSWORD");
        try { s.authType = SessionInfo.AuthType.valueOf(at); }
        catch (IllegalArgumentException e) { s.authType = SessionInfo.AuthType.PASSWORD; }
        return Optional.of(s);
    }

    private static Path dir(String group) {
        return (group == null || group.isBlank()) ? BASE : BASE.resolve(sanitize(group));
    }

    private static Stream<Path> safeList(Path dir) {
        try { return Files.list(dir); }
        catch (IOException e) { return Stream.empty(); }
    }

    private static int parsePort(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 22; }
    }
    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }
    private static int[] parseRgb(String s) {
        try {
            String[] p = s.split(",");
            return new int[]{ Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()) };
        } catch (Exception e) { return new int[]{ 204, 204, 204 }; }
    }

    public static String sanitize(String name) {
        return name.replaceAll("[^\\w\\-. ]", "_").trim();
    }
}
