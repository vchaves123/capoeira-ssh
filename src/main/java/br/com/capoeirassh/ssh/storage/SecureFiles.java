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
        Files.createDirectories(path.getParent());
        if (POSIX) {
            // Write to a temp file then atomically move to avoid a window where
            // the file exists but has wrong permissions.
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.write(tmp, data,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
            Files.setPosixFilePermissions(tmp, FILE_PERMS);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } else {
            Files.write(path, data,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
            restrictWindows(path);
        }
    }

    /**
     * Open (or create) a file for appending with owner-only permissions.
     * If the file does not yet exist, it is created with restricted permissions before opening.
     */
    public static OutputStream openAppend(Path path) throws IOException {
        Files.createDirectories(path.getParent());
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
     * On Windows, creates normally (Windows uses ACLs, not POSIX bits).
     */
    public static void createDirectories(Path dir) throws IOException {
        if (POSIX) {
            // Create each missing segment with restricted permissions.
            Path current = dir.isAbsolute() ? dir.getRoot() : Path.of("");
            for (Path segment : dir) {
                current = current.resolve(segment);
                if (!Files.exists(current)) {
                    Files.createDirectory(current,
                        PosixFilePermissions.asFileAttribute(DIR_PERMS));
                }
            }
        } else {
            Files.createDirectories(dir);
        }
    }

    /**
     * Restrict a file to the owner only on Windows. java.io.File.setReadable/Writable does
     * not touch NTFS ACLs (setReadable(false,..) is a silent no-op there), so we use icacls
     * to remove inherited ACEs (/inheritance:r) and grant the owner Full control (/grant:r).
     * Arguments are passed to icacls directly (no shell), so the path is not interpolated.
     * Falls back to the legacy best-effort DOS-attribute calls if icacls is unavailable.
     */
    private static void restrictWindows(Path path) {
        try {
            String owner = Files.getOwner(path).getName();   // e.g. "MACHINE\\user"
            Process p = new ProcessBuilder("icacls", path.toString(),
                    "/inheritance:r", "/grant:r", owner + ":F")
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
