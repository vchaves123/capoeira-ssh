package br.com.capoeirassh.ssh.storage;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sibling bug to {@link SecureFilesDirectoryPermissionsTest}: {@link SecureFiles#openAppend}
 * auto-created its target's parent directory via the JDK's {@code Files.createDirectories(...)}
 * (default-umask permissions) instead of the class's own {@link SecureFiles#createDirectories}
 * (which explicitly sets {@code rwx-------}) — so a freshly auto-created {@code ~/.capoeira/log/}
 * (the only real caller, via {@code Main.redirectConsoleToLog()}) could end up group/world-
 * readable depending on the process umask, even though {@code app.log} itself was already
 * created with owner-only permissions.
 *
 * <p>Same platform caveat as {@link SecureFilesDirectoryPermissionsTest}: no observable
 * difference on this project's Windows dev machine, so this is guarded by
 * {@link Assumptions#assumeTrue} and shows as SKIPPED here — runs red-then-green on
 * Linux/macOS/WSL.
 */
class SecureFilesOpenAppendDirectoryPermissionsTest {

    @Test
    void openAppend_createsMissingParentDirectoryWithOwnerOnlyPermissions() throws Exception {
        Assumptions.assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX-only assertion — SecureFiles.createDirectories()'s restrictive-permission "
              + "branch never runs on this filesystem");

        Path base = Files.createTempDirectory("securefiles-openappend-dir-test");
        Path newlyCreatedDir = base.resolve("capoeira-like-log-dir");
        Path target = newlyCreatedDir.resolve("app.log");
        assertFalse(Files.exists(newlyCreatedDir), "test setup: directory must not pre-exist");

        try (OutputStream out = SecureFiles.openAppend(target)) {
            out.write("hello".getBytes());
        }

        assertTrue(Files.exists(newlyCreatedDir), "openAppend() must have auto-created the parent directory");
        assertEquals(PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(newlyCreatedDir),
                "parent directory auto-created by openAppend() must be owner-only (rwx-------), "
              + "not whatever the process umask defaults to");
    }
}
