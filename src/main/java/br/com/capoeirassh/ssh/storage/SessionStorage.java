package br.com.capoeirassh.ssh.storage;

import br.com.capoeirassh.ssh.model.SessionInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Persists sessions as *.session Properties files under $HOME/.capoeira/.
 * Groups map to subdirectories.
 *
 *   ~/.capoeira/
 *   ├── {uuid}.session          ← root-level session
 *   └── production/
 *       └── {uuid}.session      ← session inside group "production"
 */
public final class SessionStorage {

    private static final Path BASE = Path.of(System.getProperty("user.home"), ".capoeira", "sessions");
    private static final String EXT = ".session";

    private SessionStorage() {}

    // -----------------------------------------------------------------------
    // Save / Delete
    // -----------------------------------------------------------------------

    public static void save(SessionInfo s) throws IOException {
        Path dir = dir(s.group);
        SecureFiles.createDirectories(dir);

        Properties p = new Properties();
        p.setProperty("id",       s.id);
        p.setProperty("name",     s.name);
        p.setProperty("host",     s.host);
        p.setProperty("port",     String.valueOf(s.port));
        p.setProperty("username", s.username);
        p.setProperty("authType", s.authType.name());
        p.setProperty("keyPath",      s.keyPath      != null ? s.keyPath      : "");
        p.setProperty("group",        s.group        != null ? s.group        : "");
        p.setProperty("iconType",     s.iconType     != null ? s.iconType     : "");
        p.setProperty("credentialId", s.credentialId != null ? s.credentialId : "");
        p.setProperty("appearFontSize", String.valueOf(s.appearFontSize));
        p.setProperty("appearFontName", s.appearFontName != null ? s.appearFontName : "");
        p.setProperty("appearFg", s.appearFgR + "," + s.appearFgG + "," + s.appearFgB);
        p.setProperty("appearBg", s.appearBgR + "," + s.appearBgG + "," + s.appearBgB);
        p.setProperty("logEnabled",  String.valueOf(s.logEnabled));
        p.setProperty("logDir",      s.logDir      != null ? s.logDir      : "");
        p.setProperty("logFileName", s.logFileName  != null ? s.logFileName : "");
        p.setProperty("terminalType",  s.terminalType != null && !s.terminalType.isBlank() ? s.terminalType : "xterm-256color");
        p.setProperty("backspaceCode", String.valueOf(s.backspaceCode));

        Path file = dir.resolve(s.fileName());
        try (OutputStream out = SecureFiles.openAppend(file)) {
            // openAppend creates the file with restricted permissions;
            // we truncate + rewrite by deleting first to avoid stale keys.
            out.close();
        }
        // Delete then rewrite so Properties.store always produces a fresh file.
        Files.deleteIfExists(file);
        try (OutputStream out = SecureFiles.openAppend(file)) {
            p.store(out, "Capoeira SSH session");
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
        SecureFiles.createDirectories(BASE.resolve(sanitize(groupName)));
    }

    public static void deleteGroup(String groupName) throws IOException {
        Path groupDir = BASE.resolve(sanitize(groupName));
        if (!Files.exists(groupDir)) return;
        // Only delete if empty (no sessions left)
        try (Stream<Path> s = Files.list(groupDir)) {
            if (s.findAny().isEmpty()) Files.delete(groupDir);
        }
    }

    /**
     * Renames a group by moving its directory (and every session file inside it) to
     * the new sanitized name. Session.group is derived from the directory name on
     * load, so the *.session files themselves don't need to be rewritten.
     */
    public static void renameGroup(String oldName, String newName) throws IOException {
        if (oldName == null || oldName.isBlank() || newName == null || newName.isBlank()) return;
        Path oldDir = BASE.resolve(sanitize(oldName));
        Path newDir = BASE.resolve(sanitize(newName));
        if (oldDir.equals(newDir) || !Files.exists(oldDir)) return;

        SecureFiles.createDirectories(newDir);
        try (Stream<Path> files = Files.list(oldDir)) {
            for (Path f : files.toList()) {
                Files.move(f, newDir.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        try (Stream<Path> remaining = Files.list(oldDir)) {
            if (remaining.findAny().isEmpty()) Files.delete(oldDir);
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
        s.iconType      = p.getProperty("iconType", "");
        s.credentialId   = p.getProperty("credentialId", "");
        s.appearFontSize = parseInt(p.getProperty("appearFontSize", "0"));
        s.appearFontName = p.getProperty("appearFontName", "");
        int[] fg = parseRgb(p.getProperty("appearFg", "204,204,204"));
        s.appearFgR = fg[0]; s.appearFgG = fg[1]; s.appearFgB = fg[2];
        int[] bg = parseRgb(p.getProperty("appearBg", "0,0,0"));
        s.appearBgR = bg[0]; s.appearBgG = bg[1]; s.appearBgB = bg[2];
        s.logEnabled  = Boolean.parseBoolean(p.getProperty("logEnabled", "false"));
        s.logDir      = p.getProperty("logDir",      "");
        s.logFileName = p.getProperty("logFileName", "");
        s.terminalType  = p.getProperty("terminalType", "xterm-256color");
        s.backspaceCode = parseInt(p.getProperty("backspaceCode", "127"));
        if (s.backspaceCode != 0x08 && s.backspaceCode != 0x7F) s.backspaceCode = 0x7F;
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
        String s = name.replaceAll("[^\\w\\-. ]", "_").trim();
        // "." and ".." are special path segments — left unblocked, a group named ".."
        // would resolve outside sessions/ into ~/.capoeira itself (alongside the vault).
        if (s.isEmpty() || s.equals(".") || s.equals("..")) return "_group";
        return s;
    }
}
