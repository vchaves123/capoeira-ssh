package br.com.capoeirassh.ssh.storage;

import br.com.capoeirassh.ssh.model.SessionInfo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code BackupBundle}'s Zip Slip fix (July 2026 security audit, finding #16, build 135): a
 * {@code sessions/*.session} ZIP entry's raw path is never used to build a filesystem path.
 * {@code fromProps()} takes only the entry name's basename (splitting on both {@code /} and
 * {@code \} — a bundle can smuggle Windows-style traversal past a {@code /}-only split) and
 * validates it via {@code isSafeId()} (word chars/dot/dash only, never {@code .}/{@code ..}/blank),
 * falling back to a fresh random UUID otherwise. Never had a dedicated regression test proving
 * a traversal entry is actually neutralized rather than merely "probably fine by inspection".
 */
class BackupBundleZipSlipTest {

    @BeforeEach
    void verifyRedirected() {
        String home = System.getProperty("user.home");
        Assumptions.assumeTrue(home != null && home.contains("test-home"),
                "user.home was not redirected to a test directory (got: " + home + ") — refusing "
              + "to run a test that resolves paths under the real sessions directory. Run via `mvn test`.");
    }

    private static byte[] buildZipWithTraversalEntry(String entryName) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(("name=Evil Session\nhost=evil.example.com\nport=22\nusername=mallory\n")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return baos.toByteArray();
    }

    private static byte[] encrypt(byte[] zipBytes, char[] password) throws Exception {
        Method m = BackupBundle.class.getDeclaredMethod("encrypt", byte[].class, char[].class);
        m.setAccessible(true);
        return (byte[]) m.invoke(null, zipBytes, password);
    }

    private static SessionInfo importSingleEntry(String entryName) throws Exception {
        char[] password = "backup-pw".toCharArray();
        byte[] zipBytes = buildZipWithTraversalEntry(entryName);
        byte[] bundleBytes = encrypt(zipBytes, password.clone());

        Path tmp = Files.createTempFile("backupbundle-zipslip-test", ".capoeira-backup");
        try {
            Files.write(tmp, bundleBytes);
            BackupBundle.ImportResult result = BackupBundle.importBundle(tmp, password.clone());
            assertEquals(1, result.sessions().size(), "the entry must still be imported (under a safe id), not dropped or thrown on");
            return result.sessions().get(0);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void unixStyleTraversalEntry_neverProducesAPathTraversingId() throws Exception {
        SessionInfo imported = importSingleEntry("sessions/../../../../etc/evil.session");
        assertFalse(imported.id.contains("/"), "imported session id must never contain a path separator: " + imported.id);
        assertFalse(imported.id.contains(".."), "imported session id must never contain a traversal segment: " + imported.id);
    }

    @Test
    void windowsStyleBackslashTraversalEntry_neverProducesAPathTraversingId() throws Exception {
        // A backslash is a path separator on Windows but not treated specially by ZipEntry
        // itself — a bundle built on/for a different platform could still smuggle one through.
        SessionInfo imported = importSingleEntry("sessions/..\\..\\..\\..\\evil.session");
        assertFalse(imported.id.contains("/"));
        assertFalse(imported.id.contains("\\"), "imported session id must never contain a backslash: " + imported.id);
        assertFalse(imported.id.contains(".."));
    }

    @Test
    void entryNameWithBlankBasename_fallsBackToARandomSafeId() throws Exception {
        // "sessions/../.session" ends with ".session" (so it isn't filtered out before reaching
        // fromProps, unlike a bare "sessions/.." which the outer suffix check already rejects)
        // but its basename is just ".session", stripping to a blank id — isSafeId() must reject
        // that too, not accept an empty/degenerate id.
        SessionInfo imported = importSingleEntry("sessions/../.session");
        assertTrue(imported.id.matches("[\\w.-]+") && !imported.id.isBlank() && !imported.id.equals(".")
                && !imported.id.equals(".."),
                "a degenerate (blank) basename must fall back to a fresh safe id, got: " + imported.id);
    }

    @Test
    void driveLetterAbsolutePathEntry_neverProducesAPathTraversingId() throws Exception {
        SessionInfo imported = importSingleEntry("sessions/C:\\Windows\\System32\\evil.session");
        assertFalse(imported.id.contains(":"), "imported session id must never contain a drive-letter separator: " + imported.id);
        assertFalse(imported.id.contains("\\"));
    }

    @Test
    void importedSessionsAlwaysSaveInsideTheSessionsRoot_regardlessOfEntryName() throws Exception {
        // End-to-end: the imported session must actually be writable/readable back through
        // SessionStorage without ever resolving outside its root — the real-world consequence
        // Zip Slip protection exists to prevent (arbitrary file write/read via backup import).
        SessionInfo imported = importSingleEntry("sessions/../../../../evil.session");
        try {
            SessionStorage.save(imported);
            var loaded = SessionStorage.loadAll().stream()
                    .filter(s -> s.id.equals(imported.id)).findFirst();
            assertTrue(loaded.isPresent(), "the safely-renamed session must save/load normally");
        } finally {
            try { SessionStorage.delete(imported); } catch (Exception ignored) {}
        }
    }
}
