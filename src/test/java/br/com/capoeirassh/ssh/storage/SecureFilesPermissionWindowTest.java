package br.com.capoeirassh.ssh.storage;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * POSIX-only: {@link SecureFiles#write} used to create its {@code .tmp} file via
 * {@code Files.write(..., CREATE, ...)} (default-umask permissions) and only apply
 * {@code rw-------} afterward via a separate {@code setPosixFilePermissions} call — a real
 * window where the temp file existed on disk with looser-than-owner-only permissions.
 *
 * <p>This test's code path never executes on Windows ({@code SecureFiles.POSIX} is false there),
 * so it is guarded by an {@link Assumptions#assumeTrue} and shows as SKIPPED on this project's
 * Windows dev machine — it is nonetheless a real test that exercises the vulnerable window (via
 * a background poller racing 200 writes) and will actually run red-then-green on Linux/macOS/WSL,
 * which this project also targets (see the {@code linux}/{@code macos} Maven profiles).
 */
class SecureFilesPermissionWindowTest {

    @Test
    void write_neverExposesTmpFileWithLooserThanOwnerOnlyPermissions() throws Exception {
        Assumptions.assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX-only code path — SecureFiles.write()'s temp-file branch never runs on this filesystem");

        Path dir    = Files.createTempDirectory("securefiles-test");
        Path target = dir.resolve("vault.bin");
        Path tmp    = dir.resolve("vault.bin.tmp");
        Set<PosixFilePermission> allowed = PosixFilePermissions.fromString("rw-------");

        AtomicBoolean sawLooserPermissions = new AtomicBoolean(false);
        AtomicReference<Set<PosixFilePermission>> observed = new AtomicReference<>();
        AtomicBoolean stop = new AtomicBoolean(false);

        Thread poller = new Thread(() -> {
            while (!stop.get()) {
                try {
                    if (Files.exists(tmp)) {
                        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(tmp, LinkOption.NOFOLLOW_LINKS);
                        if (!allowed.containsAll(perms)) {
                            sawLooserPermissions.set(true);
                            observed.set(perms);
                        }
                    }
                } catch (IOException ignored) {
                    // tmp replaced/moved mid-check by the writer — keep polling
                }
            }
        }, "tmp-permission-poller");
        poller.start();
        try {
            // Repeat many times to raise the odds of the poller sampling mid-creation.
            for (int i = 0; i < 300; i++) {
                SecureFiles.write(target, ("payload-" + i).getBytes());
            }
        } finally {
            stop.set(true);
            poller.join(5_000);
        }

        assertFalse(sawLooserPermissions.get(),
                "tmp file was observed with permissions looser than rw------- : " + observed.get());
    }
}
