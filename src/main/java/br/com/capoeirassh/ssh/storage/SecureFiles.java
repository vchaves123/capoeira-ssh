package br.com.capoeirassh.ssh.storage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * File-system helpers that restrict sensitive files to owner-only access.
 *
 * On POSIX (Linux/macOS): uses rw------- (600) for files, rwx------ (700) for dirs.
 * On Windows: uses File.setReadable/Writable(false, false) then (true, true)
 *             to remove "everyone" access and keep owner access only.
 */
public final class SecureFiles {

    private static final boolean POSIX =
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private static final Set<PosixFilePermission> FILE_PERMS =
        PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> DIR_PERMS =
        PosixFilePermissions.fromString("rwx------");

    private SecureFiles() {}

    /**
     * Write bytes to a file, creating or replacing it, with owner-only permissions.
     */
    public static void write(Path path, byte[] data) throws IOException {
        createDirectories(path.getParent());
        if (POSIX) {
            // Write to a temp file then atomically move to avoid a window where the file exists
            // but has wrong permissions. The temp file's permissions are set atomically at
            // creation (via the FileAttribute overload, applied by the same open(2)/creat syscall
            // that creates the file) rather than by a separate setPosixFilePermissions() call
            // afterward — the latter would leave the file briefly readable at the process umask's
            // default permissions before being restricted.
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.deleteIfExists(tmp); // clear a stale temp file from a prior crash before recreating
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(FILE_PERMS));
            try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                out.write(data);
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } else {
            // Same temp-file + atomic-move approach as POSIX, so a crash/power-loss mid-write
            // can never leave the destination truncated or half-written — only ever the old
            // content or the fully-written new content, never something in between.
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.write(tmp, data,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictWindows(path);
        }
    }

    /**
     * Open (or create) a file for appending with owner-only permissions.
     * If the file does not yet exist, it is created with restricted permissions before opening.
     */
    public static OutputStream openAppend(Path path) throws IOException {
        createDirectories(path.getParent());
        if (POSIX) {
            if (!Files.exists(path)) {
                Files.createFile(path,
                    PosixFilePermissions.asFileAttribute(FILE_PERMS));
            }
        } else {
            if (!Files.exists(path)) {
                Files.createFile(path);
                restrictWindows(path);
            }
        }
        return Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Create a directory (and parents) with owner-only permissions.
     * On Windows, each newly created segment gets the same owner-only ACL restriction as a file
     * (see {@link #restrictWindows}) — Windows uses ACLs, not POSIX bits, but a directory under
     * {@code ~/.capoeira} (sessions, traces, etc.) must not simply inherit whatever ACL its parent
     * happens to have, the same way a file created there never does.
     */
    public static void createDirectories(Path dir) throws IOException {
        // Create each missing segment individually, restricting only the ones we actually create
        // — an already-existing ancestor (e.g. the user's own profile directory) is left alone on
        // both platforms.
        Path current = dir.isAbsolute() ? dir.getRoot() : Path.of("");
        for (Path segment : dir) {
            current = current.resolve(segment);
            if (!Files.exists(current)) {
                if (POSIX) {
                    Files.createDirectory(current, PosixFilePermissions.asFileAttribute(DIR_PERMS));
                } else {
                    Files.createDirectory(current);
                    restrictWindows(current);
                }
            }
        }
    }

    /**
     * Restrict a file OR directory to the owner only on Windows. java.io.File.setReadable/Writable
     * does not touch NTFS ACLs (setReadable(false,..) is a silent no-op there), so we use icacls
     * to remove inherited ACEs (/inheritance:r) and grant the owner Full control (/grant:r).
     * Arguments are passed to icacls directly (no shell), so the path is not interpolated.
     * Falls back to the legacy best-effort DOS-attribute calls if icacls is unavailable.
     *
     * <p>For a directory, the grant is made inheritable ({@code (OI)(CI)} — object-inherit,
     * container-inherit) so a file or subdirectory created inside it later actually inherits the
     * owner-only ACE via real NTFS inheritance. Without this, a plain non-inheritable grant (fine
     * for a file, which has no children) leaves a newly created child with none of its parent's
     * restriction to inherit at all — Windows then falls back to that child's default security
     * descriptor, which routinely includes {@code BUILTIN\Administrators}/{@code NT AUTHORITY\
     * SYSTEM} as ordinary (non-inherited) explicit entries that {@code /inheritance:r} + a plain
     * {@code /grant:r owner:F} on the CHILD wouldn't remove either, since {@code /inheritance:r}
     * only strips entries actually marked inherited, and {@code /grant:r} only replaces the named
     * principal's own entries, never removes a different principal's.
     */
    private static void restrictWindows(Path path) {
        boolean isDir = Files.isDirectory(path);
        try {
            String owner = Files.getOwner(path).getName();   // e.g. "MACHINE\\user"
            String grant = owner + (isDir ? ":(OI)(CI)F" : ":F");
            Process p = new ProcessBuilder("icacls", path.toString(),
                    "/inheritance:r", "/grant:r", grant)
                .redirectErrorStream(true)
                .start();
            p.getInputStream().readAllBytes();   // drain output so the process can exit
            if (p.waitFor() == 0) return;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // fall through to best-effort legacy restriction
        }
        legacyRestrict(path);
    }

    /** Best-effort fallback: DOS read-only attribute toggling (does not manage NTFS ACLs). */
    private static void legacyRestrict(Path path) {
        java.io.File f = path.toFile();
        f.setReadable(false, false);
        f.setWritable(false, false);
        f.setReadable(true,  true);
        f.setWritable(true,  true);
    }
}
