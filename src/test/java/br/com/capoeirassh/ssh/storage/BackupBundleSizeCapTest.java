package br.com.capoeirassh.ssh.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code BackupBundle.importBundle()} used to call {@code Files.readAllBytes(source)} on a
 * user-selected, potentially crafted {@code .capoeira-backup} file with no upper size bound,
 * before any header/version validation ran — a sufficiently oversized file risks
 * {@code OutOfMemoryError} (which extends {@code Error}, not {@code Exception}, and so escapes
 * the plain {@code catch (Exception ex)} the import flow wraps this in), instead of the intended
 * clean "Import failed" dialog.
 *
 * <p>Uses {@link RandomAccessFile#setLength} rather than actually writing megabytes of data — it
 * only needs the file's logical size to exceed the cap, not real content, and {@code setLength}
 * is a near-instant metadata-only operation on this project's target filesystems (NTFS sparse
 * allocation on Windows; a hole on Linux/macOS).
 */
class BackupBundleSizeCapTest {

    @TempDir Path tempDir;

    private static long maxBundleFileBytes() throws Exception {
        Field f = BackupBundle.class.getDeclaredField("MAX_BUNDLE_FILE_BYTES");
        f.setAccessible(true);
        return f.getLong(null);
    }

    @Test
    void importBundle_rejectsAnOversizedFile_beforeReadingItFully() throws Exception {
        long cap = maxBundleFileBytes();
        Path oversized = tempDir.resolve("huge.capoeira-backup");
        try (RandomAccessFile raf = new RandomAccessFile(oversized.toFile(), "rw")) {
            raf.setLength(cap + 1);
        }

        IOException ex = assertThrows(IOException.class,
                () -> BackupBundle.importBundle(oversized, "whatever".toCharArray()),
                "a file larger than the cap must be refused outright, never handed to "
              + "Files.readAllBytes()");
        assertTrue(ex.getMessage().contains("too large"), "got: " + ex.getMessage());
    }

    @Test
    void importBundle_atExactlyTheCap_isNotRejectedBySizeAlone() throws Exception {
        // Not a full round-trip (the content is garbage, so decrypt() will reject it for a
        // different reason) — just proving the size check itself uses a strict ">", not ">=",
        // so a file exactly at the cap isn't refused for being "too large" when it isn't.
        long cap = maxBundleFileBytes();
        Path atCap = tempDir.resolve("at-cap.capoeira-backup");
        try (RandomAccessFile raf = new RandomAccessFile(atCap.toFile(), "rw")) {
            raf.setLength(cap);
        }

        Exception ex = assertThrows(Exception.class,
                () -> BackupBundle.importBundle(atCap, "whatever".toCharArray()));
        assertFalse(ex.getMessage() != null && ex.getMessage().contains("too large"),
                "a file exactly at the cap must fail for being garbage/corrupt, not for its size: " + ex.getMessage());
    }
}
