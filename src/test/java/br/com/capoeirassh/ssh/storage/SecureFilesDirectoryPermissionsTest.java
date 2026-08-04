package br.com.capoeirassh.ssh.storage;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;

/**
 * POSIX-only: {@link SecureFiles#write} used to auto-create its target's parent directory via
 * the JDK's {@code Files.createDirectories(...)} (default-umask permissions) instead of the
 * class's own {@link SecureFiles#createDirectories} (which explicitly sets {@code rwx-------}),
 * so a freshly auto-created {@code ~/.capoeira/} could end up group/world-readable depending on
 * the process umask.
 *
 * <p>Same platform caveat as {@link SecureFilesPermissionWindowTest}: {@code SecureFiles}'s
 * non-POSIX (Windows) branch of {@code createDirectories} just delegates to
 * {@code Files.createDirectories} either way, so there is no observable difference to assert on
 * this project's Windows dev machine — this test is guarded by {@link Assumptions#assumeTrue}
 * and shows as SKIPPED here, but runs red-then-green on Linux/macOS/WSL.
 */
class SecureFilesDirectoryPermissionsTest {

    @Test
    void write_createsMissingParentDirectoryWithOwnerOnlyPermissions() throws Exception {
        Assumptions.assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX-only assertion — SecureFiles.createDirectories()'s restrictive-permission "
              + "branch never runs on this filesystem");

        Path base = Files.createTempDirectory("securefiles-dir-test");
        Path newlyCreatedDir = base.resolve("capoeira-like-dir");
        Path target = newlyCreatedDir.resolve("vault.bin");
        assertFalse(Files.exists(newlyCreatedDir), "test setup: directory must not pre-exist");

        SecureFiles.write(target, "hello".getBytes());

        assertTrue(Files.exists(newlyCreatedDir), "write() must have auto-created the parent directory");
        assertEquals(PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(newlyCreatedDir),
                "parent directory auto-created by write() must be owner-only (rwx-------), "
              + "not whatever the process umask defaults to");
    }
}
