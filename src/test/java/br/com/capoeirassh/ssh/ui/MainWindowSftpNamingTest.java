package br.com.capoeirassh.ssh.ui;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code MainWindow}'s upload/download conflict-resolution helpers — {@code remoteExists},
 * {@code joinRemote}, {@code uniqueRemoteName} and {@code uniqueLocalName} — extracted to
 * package-private static methods so they're testable without a {@code Display}/SWT dependency,
 * same convention as {@code ImportSessionsDialogDedupeTest}.
 *
 * <p>{@code ChannelSftp} has a public no-arg constructor and a non-final {@code stat(String)},
 * so the remote-side tests fake it with a subclass rather than needing a mocking library.
 */
class MainWindowSftpNamingTest {

    /** Reports a fixed set of paths as existing; everything else as SSH_FX_NO_SUCH_FILE, just
     *  like a real server's stat() would for a path that isn't there. */
    private static final class FakeSftp extends ChannelSftp {
        private final Set<String> existingPaths;
        FakeSftp(String... existingPaths) { this.existingPaths = new HashSet<>(Set.of(existingPaths)); }

        @Override
        public SftpATTRS stat(String path) throws SftpException {
            if (!existingPaths.contains(path))
                throw new SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "no such file: " + path);
            return null; // never inspected by remoteExists() — only whether stat() throws matters
        }
    }

    // ── remoteExists ─────────────────────────────────────────────────────────

    @Test
    void remoteExists_true_whenStatSucceeds() {
        FakeSftp sftp = new FakeSftp("/home/user/report.txt");
        assertTrue(MainWindow.remoteExists(sftp, "/home/user/report.txt"));
    }

    @Test
    void remoteExists_false_whenStatThrowsNoSuchFile() {
        FakeSftp sftp = new FakeSftp("/home/user/report.txt");
        assertFalse(MainWindow.remoteExists(sftp, "/home/user/missing.txt"));
    }

    // ── joinRemote ───────────────────────────────────────────────────────────

    @Test
    void joinRemote_dirWithoutTrailingSlash_insertsOne() {
        assertEquals("/home/user/file.txt", MainWindow.joinRemote("/home/user", "file.txt"));
    }

    @Test
    void joinRemote_dirWithTrailingSlash_doesNotDouble() {
        assertEquals("/home/user/file.txt", MainWindow.joinRemote("/home/user/", "file.txt"));
    }

    // ── uniqueRemoteName ─────────────────────────────────────────────────────

    @Test
    void uniqueRemoteName_noConflict_stillAddsSuffix() {
        // Callers only invoke this once a conflict on the original name is already confirmed, so
        // it always produces a "(1)"-suffixed candidate rather than trying the bare name first.
        FakeSftp sftp = new FakeSftp("/uploads/report.txt");
        assertEquals("report (1).txt", MainWindow.uniqueRemoteName(sftp, "/uploads", "report.txt"));
    }

    @Test
    void uniqueRemoteName_incrementsPastExistingSuffixes() {
        FakeSftp sftp = new FakeSftp(
            "/uploads/report.txt", "/uploads/report (1).txt", "/uploads/report (2).txt");
        assertEquals("report (3).txt", MainWindow.uniqueRemoteName(sftp, "/uploads", "report.txt"));
    }

    @Test
    void uniqueRemoteName_withoutExtension_stillGetsASuffix() {
        FakeSftp sftp = new FakeSftp("/uploads/README");
        assertEquals("README (1)", MainWindow.uniqueRemoteName(sftp, "/uploads", "README"));
    }

    // ── uniqueLocalName ──────────────────────────────────────────────────────

    @Test
    void uniqueLocalName_incrementsPastExistingSuffixes(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("report.txt"));
        Files.createFile(tempDir.resolve("report (1).txt"));
        String dir = tempDir.toString();
        assertEquals("report (2).txt", MainWindow.uniqueLocalName(dir, "report.txt"));
    }

    @Test
    void uniqueLocalName_withoutExtension_stillGetsASuffix(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("README"));
        assertEquals("README (1)", MainWindow.uniqueLocalName(tempDir.toString(), "README"));
    }

    // ── isWithinDir ──────────────────────────────────────────────────────────
    // Last-line-of-defense containment check backing downloadFiles()'s path-traversal fix —
    // see RemoteFileBrowserDialogPathTest's PickedFile.name() tests for the matching sanitize-at-
    // the-source half of the same fix.

    @Test
    void isWithinDir_plainFileDirectlyInsideDir_isWithin(@TempDir Path tempDir) {
        String dir = tempDir.toString();
        assertTrue(MainWindow.isWithinDir(dir, new java.io.File(dir, "report.txt")));
    }

    @Test
    void isWithinDir_nestedTraversalEscapingDir_isNotWithin(@TempDir Path tempDir) {
        String dir = tempDir.toString();
        // Mirrors what a name() that failed to strip a backslash-laden entry would have produced.
        assertFalse(MainWindow.isWithinDir(dir, new java.io.File(dir, "..\\..\\evil.exe")));
    }

    @Test
    void isWithinDir_bareParentSegment_isNotWithin(@TempDir Path tempDir) {
        String dir = tempDir.toString();
        assertFalse(MainWindow.isWithinDir(dir, new java.io.File(dir, "..")));
    }

    @Test
    void isWithinDir_dirItself_isNotWithin(@TempDir Path tempDir) {
        String dir = tempDir.toString();
        assertFalse(MainWindow.isWithinDir(dir, new java.io.File(dir, ".")));
    }
}
