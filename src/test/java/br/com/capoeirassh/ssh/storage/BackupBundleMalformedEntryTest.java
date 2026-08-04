package br.com.capoeirassh.ssh.storage;

import br.com.capoeirassh.ssh.model.SessionInfo;
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
 * {@code BackupBundle.unzip()} had no per-entry {@code try/catch} around parsing each
 * {@code sessions/*.session} ZIP entry as a {@link java.util.Properties} file. A single
 * malformed entry (e.g. a corrupted or hand-crafted backslash-escape sequence in the Properties
 * text format — {@code Properties.load()} throws {@code IllegalArgumentException} for that, not
 * {@code IOException}) propagated out of the whole {@code unzip()} loop, aborting the entire
 * import — every other valid session in the same backup was lost too, not just the bad entry.
 *
 * <p>This test builds a bundle with one well-formed session entry and one entry containing an
 * invalid backslash-escape (built via char concatenation below, deliberately avoiding writing
 * the literal escape sequence in this Java source, since javac itself pre-processes Unicode
 * escapes in source text before compiling), encrypts it with the class's own
 * (package-private-via-reflection) {@code encrypt()}, and asserts {@code importBundle()} still
 * returns the good session.
 */
class BackupBundleMalformedEntryTest {

    private static byte[] buildZipWithMalformedEntry() throws Exception {
        // Deliberately built via char concatenation, not a string literal — javac itself
        // pre-processes "backslash followed by u" as a Unicode escape at the lexer level
        // (even inside string literals/comments), so writing the actual invalid escape
        // sequence directly in this source file would fail to compile.
        String invalidEscape = "name=Bad" + '\\' + "uZZZZSession\nhost=bad.example.com\n";

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            zip.putNextEntry(new ZipEntry("sessions/good-id.session"));
            zip.write(("name=Good Session\nhost=good.example.com\nport=22\nusername=alice\n")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("sessions/bad-id.session"));
            zip.write(invalidEscape.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return baos.toByteArray();
    }

    private static byte[] encrypt(byte[] zipBytes, char[] password) throws Exception {
        Method m = BackupBundle.class.getDeclaredMethod("encrypt", byte[].class, char[].class);
        m.setAccessible(true);
        return (byte[]) m.invoke(null, zipBytes, password);
    }

    @Test
    void importBundle_skipsOneMalformedSessionEntry_stillImportsTheGoodOne() throws Exception {
        char[] password = "backup-pw".toCharArray();
        byte[] zipBytes = buildZipWithMalformedEntry();
        byte[] bundleBytes = encrypt(zipBytes, password.clone());

        Path tmp = Files.createTempFile("backupbundle-malformed-entry-test", ".capoeira-backup");
        try {
            Files.write(tmp, bundleBytes);

            BackupBundle.ImportResult result = assertDoesNotThrow(
                    () -> BackupBundle.importBundle(tmp, password.clone()),
                    "one malformed session entry must not make the whole import throw");

            assertEquals(1, result.sessions().size(),
                    "expected exactly the one well-formed session to survive: " + result.sessions());
            SessionInfo good = result.sessions().get(0);
            assertEquals("Good Session", good.name);
            assertEquals("good.example.com", good.host);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
