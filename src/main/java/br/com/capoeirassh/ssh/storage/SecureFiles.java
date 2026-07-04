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

    private static void restrictWindows(Path path) {
        java.io.File f = path.toFile();
        f.setReadable(false, false);   // revoke everyone
        f.setWritable(false, false);
        f.setReadable(true,  true);    // grant owner only
        f.setWritable(true,  true);
    }
}
