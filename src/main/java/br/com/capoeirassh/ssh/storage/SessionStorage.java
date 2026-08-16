package br.com.capoeirassh.ssh.storage;

import br.com.capoeirassh.ssh.model.SessionInfo;

import java.io.IOException;
import java.io.InputStream;
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

    /** Schema version of the *.session Properties format written by save() below. Absent on
     *  every file written before this field existed — load() treats a missing value as
     *  compatible (implicitly this version or earlier), but explicitly rejects a value greater
     *  than SCHEMA_VERSION, i.e. a file written by a future version of this program whose format
     *  this build doesn't understand. Without this, a schema change in a newer version (a
     *  renamed key, a changed value encoding) would otherwise be silently misread as all-defaults
     *  by an older build instead of being detected and refused. */
    private static final int SCHEMA_VERSION = 1;

    private SessionStorage() {}

    // -----------------------------------------------------------------------
    // Save / Delete
    // -----------------------------------------------------------------------

    public static void save(SessionInfo s) throws IOException {
        Path dir = dir(s.group);
        SecureFiles.createDirectories(dir);

        Properties p = new Properties();
        p.setProperty("schemaVersion", String.valueOf(SCHEMA_VERSION));
        p.setProperty("id",       s.id);
        p.setProperty("name",     s.name);
        p.setProperty("host",     s.host);
        p.setProperty("port",     String.valueOf(s.port));
        p.setProperty("username", s.username);
        p.setProperty("authType", s.authType.name());
        p.setProperty("keyPath",      Objects.requireNonNullElse(s.keyPath,      ""));
        p.setProperty("group",        Objects.requireNonNullElse(s.group,        ""));
        p.setProperty("iconType",     Objects.requireNonNullElse(s.iconType,     ""));
        p.setProperty("credentialId", Objects.requireNonNullElse(s.credentialId, ""));
        p.setProperty("appearFontSize", String.valueOf(s.appearFontSize));
        p.setProperty("appearFontName", Objects.requireNonNullElse(s.appearFontName, ""));
        p.setProperty("appearFg", s.appearFgR + "," + s.appearFgG + "," + s.appearFgB);
        p.setProperty("appearBg", s.appearBgR + "," + s.appearBgG + "," + s.appearBgB);
        p.setProperty("logEnabled",  String.valueOf(s.logEnabled));
        p.setProperty("logDir",      Objects.requireNonNullElse(s.logDir,      ""));
        p.setProperty("logFileName", Objects.requireNonNullElse(s.logFileName, ""));
        p.setProperty("terminalType",  s.terminalType != null && !s.terminalType.isBlank() ? s.terminalType : "xterm-256color");
        p.setProperty("backspaceCode", String.valueOf(s.backspaceCode));
        p.setProperty("sshVerbose",    String.valueOf(s.sshVerbose));
        p.setProperty("allowColumnMode", String.valueOf(s.allowColumnMode));
        p.setProperty("sortOrder",     String.valueOf(s.sortOrder));
        p.setProperty("tags",          String.join(",", s.tags));
        for (String tag : s.tags) TagRegistry.register(tag);
        p.setProperty("connectionType",    s.connectionType.name());
        p.setProperty("serialPortName",    Objects.requireNonNullElse(s.serialPortName, ""));
        p.setProperty("serialBaudRate",    String.valueOf(s.serialBaudRate));
        p.setProperty("serialDataBits",    String.valueOf(s.serialDataBits));
        p.setProperty("serialParity",      s.serialParity.name());
        p.setProperty("serialStopBits",    String.valueOf(s.serialStopBits));
        p.setProperty("serialFlowControl", s.serialFlowControl.name());
        p.setProperty("serialLocalEcho",   String.valueOf(s.serialLocalEcho));

        Path file = dir.resolve(s.fileName());
        // Serialize to a byte[] first, then write via SecureFiles' temp-file + atomic-move —
        // the previous delete-then-reopen approach left a window where the file was briefly
        // absent, so a crash/power-loss mid-write could permanently lose the session.
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        p.store(baos, "Capoeira SSH session");
        SecureFiles.write(file, baos.toByteArray());
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

        // Self-heals TagRegistry against sessions saved by an older build (before the
        // registry existed) or a manually-restored/edited *.session file — every tag any
        // session carries ends up registered, register() being a no-op if already known.
        for (SessionInfo s : list) for (String tag : s.tags) TagRegistry.register(tag);

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
                Path target = newDir.resolve(f.getFileName());
                // Session filenames are random UUIDs, so a same-name collision here should be
                // essentially impossible in normal use — but REPLACE_EXISTING would silently
                // destroy whatever's already there if it ever did happen (e.g. a hand-restored
                // or duplicated backup). Leave the source file behind in oldDir instead of
                // clobbering; every other file still moves normally.
                if (Files.exists(target)) continue;
                Files.move(f, target);
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

        // A file with no schemaVersion property is one written before this field existed —
        // treated as compatible. A present value greater than SCHEMA_VERSION means a future
        // version of this program wrote it in a format this build doesn't understand; refuse it
        // rather than silently loading whatever partial/blank fields getProperty()'s defaults
        // produce for keys this build doesn't recognize.
        String verStr = p.getProperty("schemaVersion");
        if (verStr != null) {
            int ver;
            try { ver = Integer.parseInt(verStr.trim()); } catch (NumberFormatException e) { return Optional.empty(); }
            if (ver > SCHEMA_VERSION) return Optional.empty();
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
        s.sshVerbose    = Boolean.parseBoolean(p.getProperty("sshVerbose", "false"));
        // Defaults to true, so sessions saved before this option existed get the new behaviour.
        s.allowColumnMode = Boolean.parseBoolean(p.getProperty("allowColumnMode", "true"));
        s.sortOrder     = parseInt(p.getProperty("sortOrder", "0"));
        s.tags          = parseTags(p.getProperty("tags", ""));
        String at        = p.getProperty("authType", "PASSWORD");
        try { s.authType = SessionInfo.AuthType.valueOf(at); }
        catch (IllegalArgumentException e) { s.authType = SessionInfo.AuthType.PASSWORD; }
        try { s.connectionType = SessionInfo.ConnectionType.valueOf(p.getProperty("connectionType", "SSH")); }
        catch (IllegalArgumentException e) { s.connectionType = SessionInfo.ConnectionType.SSH; }
        s.serialPortName = p.getProperty("serialPortName", "");
        s.serialBaudRate = parseInt(p.getProperty("serialBaudRate", "9600"));
        if (s.serialBaudRate <= 0) s.serialBaudRate = 9600;
        s.serialDataBits = parseInt(p.getProperty("serialDataBits", "8"));
        try { s.serialParity = SessionInfo.SerialParity.valueOf(p.getProperty("serialParity", "NONE")); }
        catch (IllegalArgumentException e) { s.serialParity = SessionInfo.SerialParity.NONE; }
        s.serialStopBits = parseInt(p.getProperty("serialStopBits", "1"));
        if (s.serialStopBits != 1 && s.serialStopBits != 2) s.serialStopBits = 1;
        try { s.serialFlowControl = SessionInfo.SerialFlowControl.valueOf(p.getProperty("serialFlowControl", "NONE")); }
        catch (IllegalArgumentException e) { s.serialFlowControl = SessionInfo.SerialFlowControl.NONE; }
        s.serialLocalEcho = Boolean.parseBoolean(p.getProperty("serialLocalEcho", "false"));
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
    /** Comma-separated, trimmed, de-duplicated, capped at 6 — defensive against a hand-edited
     *  or corrupted *.session file, not just whatever the dialog already validated on save. */
    private static List<String> parseTags(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String t : raw.split(",")) {
            String trimmed = t.trim();
            if (!trimmed.isEmpty() && !out.contains(trimmed)) out.add(trimmed);
            if (out.size() == 6) break;
        }
        return out;
    }
    private static int[] parseRgb(String s) {
        try {
            String[] p = s.split(",");
            return new int[]{ Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()) };
        } catch (Exception e) { return new int[]{ 204, 204, 204 }; }
    }

    public static String sanitize(String name) {
        String s = name.replaceAll("[^\\w\\-. ]", "_").trim();
        // Windows silently strips trailing dots/spaces from a path segment on create — do the
        // same here so the app's own notion of a group's name never diverges from what's
        // actually on disk (otherwise "build." and "build" would resolve to the same directory
        // without the app realizing it).
        while (s.endsWith(".") || s.endsWith(" ")) s = s.substring(0, s.length() - 1);
        // "." and ".." are special path segments — left unblocked, a group named ".."
        // would resolve outside sessions/ into ~/.capoeira itself (alongside the vault).
        if (s.isEmpty() || s.equals(".") || s.equals("..")) return "_group";
        return s;
    }

    /** Finds an existing group whose on-disk (sanitized) form collides with candidateName's —
     *  typically because the two are the same up to a case-insensitive-filesystem comparison
     *  (Windows/macOS default), or because both happen to sanitize to the same string (e.g. two
     *  names differing only in non-ASCII characters, which sanitize() collapses to "_"). Returns
     *  null if there's no collision. {@code excludeSelf} lets a rename ignore the group's own
     *  current name (renaming "Test" to "test" isn't a collision with itself). */
    public static String findCollidingGroup(String candidateName, String excludeSelf) {
        if (candidateName == null || candidateName.isBlank()) return null;
        String candidateSanitized = sanitize(candidateName);
        for (String existing : loadGroups()) {
            if (existing.equals(excludeSelf)) continue;
            if (sanitize(existing).equalsIgnoreCase(candidateSanitized)) return existing;
        }
        return null;
    }
}
