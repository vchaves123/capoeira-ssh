package br.com.capoeirassh.ssh.storage;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code BackupBundle}'s header used to be self-describing (magic + version + KDF id + iteration
 * count + salt) but never bound as GCM AAD — unlike {@link CredentialStore}, which closed the
 * identical gap for its own vault format (build 249). Today every header field already
 * indirectly feeds key derivation or is independently range-checked, so there was no live
 * exploit — but a future header field that didn't happen to feed derivation would have been
 * silently tamperable without invalidating the GCM tag. This test proves the v3 (AAD-bound)
 * format actually detects header tampering, and that v1/v2 bundles exported before this fix
 * still import correctly (no live mutable file to migrate here, unlike the vault — old bundles
 * simply stay v2/v1 forever).
 */
class BackupBundleAadTest {

    @Test
    void verifyRedirected() {
        String home = System.getProperty("user.home");
        Assumptions.assumeTrue(home != null && home.contains("test-home"),
                "user.home was not redirected to a test directory (got: " + home + ") — refusing "
              + "to run a test that resolves paths under the real sessions directory. Run via `mvn test`.");
    }

    // -----------------------------------------------------------------------
    // Reflection plumbing
    // -----------------------------------------------------------------------

    private static byte[] encrypt(byte[] zipBytes, char[] password) throws Exception {
        Method m = BackupBundle.class.getDeclaredMethod("encrypt", byte[].class, char[].class);
        m.setAccessible(true);
        return (byte[]) m.invoke(null, zipBytes, password);
    }

    private static SecretKey deriveKey(char[] pw, byte[] salt, int iterations) throws Exception {
        Method m = BackupBundle.class.getDeclaredMethod("deriveKey", char[].class, byte[].class, int.class);
        m.setAccessible(true);
        try {
            return (SecretKey) m.invoke(null, pw, salt, iterations);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception ex) throw ex;
            throw e;
        }
    }

    private static <T> T staticField(String name) throws Exception {
        Field f = BackupBundle.class.getDeclaredField(name);
        f.setAccessible(true);
        @SuppressWarnings("unchecked") T val = (T) f.get(null);
        return val;
    }

    private static int staticIntField(String name) throws Exception { return staticField(name); }

    private static byte[] trivialZip() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            zip.putNextEntry(new ZipEntry("sessions/x.session"));
            zip.write("name=X\nhost=x.example.com\nport=22\nusername=u\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return baos.toByteArray();
    }

    /** Hand-assembles a v1-format bundle: magic + version(1) + salt + iv + ciphertext. */
    private static byte[] buildV1Bundle(byte[] salt, byte[] iv, byte[] ciphertext) throws Exception {
        byte[] magic = staticField("MAGIC");
        byte[] out = new byte[magic.length + 1 + salt.length + iv.length + ciphertext.length];
        int off = 0;
        System.arraycopy(magic, 0, out, off, magic.length); off += magic.length;
        out[off++] = 1; // version
        System.arraycopy(salt, 0, out, off, salt.length); off += salt.length;
        System.arraycopy(iv, 0, out, off, iv.length); off += iv.length;
        System.arraycopy(ciphertext, 0, out, off, ciphertext.length);
        return out;
    }

    /** Hand-assembles a self-describing (v2 or v3) bundle: magic + version + kdfId +
     *  iterations(4 BE) + salt + iv + ciphertext. */
    private static byte[] buildSelfDescribingBundle(int version, int kdfId, int iterations,
                                                     byte[] salt, byte[] iv, byte[] ciphertext) throws Exception {
        byte[] magic = staticField("MAGIC");
        byte[] out = new byte[magic.length + 1 + 1 + 4 + salt.length + iv.length + ciphertext.length];
        int off = 0;
        System.arraycopy(magic, 0, out, off, magic.length); off += magic.length;
        out[off++] = (byte) version;
        out[off++] = (byte) kdfId;
        out[off++] = (byte) (iterations >>> 24);
        out[off++] = (byte) (iterations >>> 16);
        out[off++] = (byte) (iterations >>> 8);
        out[off++] = (byte) iterations;
        System.arraycopy(salt, 0, out, off, salt.length); off += salt.length;
        System.arraycopy(iv, 0, out, off, iv.length); off += iv.length;
        System.arraycopy(ciphertext, 0, out, off, ciphertext.length);
        return out;
    }

    /** Builds the exact v3 self-describing header (magic+version+kdfId+iterations+salt) that
     *  encrypt()/decrypt() bind as AAD. */
    private static byte[] v3Header(int kdfId, int iterations, byte[] salt) throws Exception {
        byte[] magic = staticField("MAGIC");
        byte[] header = new byte[magic.length + 1 + 1 + 4 + salt.length];
        int off = 0;
        System.arraycopy(magic, 0, header, off, magic.length); off += magic.length;
        header[off++] = 3; // version
        header[off++] = (byte) kdfId;
        header[off++] = (byte) (iterations >>> 24);
        header[off++] = (byte) (iterations >>> 16);
        header[off++] = (byte) (iterations >>> 8);
        header[off++] = (byte) iterations;
        System.arraycopy(salt, 0, header, off, salt.length);
        return header;
    }

    private static byte[] encryptZip(byte[] zip, char[] pw, byte[] salt, int iterations, byte[] iv) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, deriveKey(pw, salt, iterations), new GCMParameterSpec(128, iv));
        return c.doFinal(zip);
    }

    private static byte[] encryptZipWithAad(byte[] zip, char[] pw, byte[] salt, int iterations, byte[] iv,
                                             byte[] header) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, deriveKey(pw, salt, iterations), new GCMParameterSpec(128, iv));
        c.updateAAD(header);
        return c.doFinal(zip);
    }

    private static Path writeTemp(byte[] bytes) throws Exception {
        Path tmp = Files.createTempFile("backupbundle-aad-test", ".capoeira-backup");
        Files.write(tmp, bytes);
        return tmp;
    }

    // -----------------------------------------------------------------------
    // export() writes the new (AAD-bound) format
    // -----------------------------------------------------------------------

    @Test
    void export_thenImport_roundTripsSuccessfully() throws Exception {
        char[] password = "backup-pw".toCharArray();
        byte[] zipBytes = trivialZip();
        byte[] bundleBytes = encrypt(zipBytes, password.clone());
        Path tmp = writeTemp(bundleBytes);
        try {
            BackupBundle.ImportResult result = BackupBundle.importBundle(tmp, password.clone());
            assertEquals(1, result.sessions().size());
            assertEquals("X", result.sessions().get(0).name);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void export_writesTheCurrentAadBoundVersionByte() throws Exception {
        byte[] bundleBytes = encrypt(trivialZip(), "pw".toCharArray());
        int magicLen = ((byte[]) staticField("MAGIC")).length;
        int currentVersion = staticIntField("VERSION");
        assertEquals(3, currentVersion, "sanity: this test assumes VERSION == 3 post-fix");
        assertEquals(currentVersion, bundleBytes[magicLen] & 0xFF,
                "encrypt() must write the current (AAD-bound) version, not the pre-fix v2 format");
    }

    // -----------------------------------------------------------------------
    // v3: header tampering must be caught
    // -----------------------------------------------------------------------

    @Test
    void importBundle_rejectsV3BundleWithTamperedHeader() throws Exception {
        byte[] salt = new byte[16], iv = new byte[12];
        new SecureRandom().nextBytes(salt);
        new SecureRandom().nextBytes(iv);
        int kdfPbkdf2 = staticIntField("KDF_PBKDF2");
        char[] password = "correct horse".toCharArray();

        byte[] header = v3Header(kdfPbkdf2, 600_000, salt);
        byte[] ciphertext = encryptZipWithAad(trivialZip(), password.clone(), salt, 600_000, iv, header);
        byte[] bundle = buildSelfDescribingBundle(3, kdfPbkdf2, 600_000, salt, iv, ciphertext);

        // Flip a bit in the on-disk salt — kdfId/iterations are independently range-checked, so
        // this reaches the AAD check rather than an earlier validation error.
        int magicLen = ((byte[]) staticField("MAGIC")).length;
        bundle[magicLen + 1 + 1 + 4] ^= 0x01; // first byte of the salt
        Path tmp = writeTemp(bundle);
        try {
            Exception ex = assertThrows(Exception.class,
                    () -> BackupBundle.importBundle(tmp, password.clone()),
                    "a v3 ciphertext whose bound header was tampered with must be rejected, even "
                  + "with the objectively correct password");
            assertTrue(ex.getMessage().contains("Wrong password or corrupt backup file"), "got: " + ex.getMessage());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // -----------------------------------------------------------------------
    // Backward compatibility: v1/v2 bundles exported before this fix
    // -----------------------------------------------------------------------

    @Test
    void importBundle_acceptsPreExistingV2BundleWithoutAad_forBackwardCompatibility() throws Exception {
        // Every bundle exported before this fix is a v2 file whose ciphertext was never bound to
        // its header via AAD — exactly what this test hand-crafts. Unlike the vault, there is no
        // live mutable file to transparently migrate on next export, so v2 (no AAD) must stay a
        // permanently readable format.
        byte[] salt = new byte[16], iv = new byte[12];
        new SecureRandom().nextBytes(salt);
        new SecureRandom().nextBytes(iv);
        int kdfPbkdf2 = staticIntField("KDF_PBKDF2");
        char[] password = "correct horse".toCharArray();

        byte[] ciphertext = encryptZip(trivialZip(), password.clone(), salt, 600_000, iv);
        byte[] bundle = buildSelfDescribingBundle(2, kdfPbkdf2, 600_000, salt, iv, ciphertext);
        Path tmp = writeTemp(bundle);
        try {
            BackupBundle.ImportResult result = assertDoesNotThrow(
                    () -> BackupBundle.importBundle(tmp, password.clone()),
                    "a pre-existing v2 (no-AAD) bundle must still import with its correct password");
            assertEquals(1, result.sessions().size());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void importBundle_acceptsLegacyV1Bundle() throws Exception {
        byte[] salt = new byte[16], iv = new byte[12];
        new SecureRandom().nextBytes(salt);
        new SecureRandom().nextBytes(iv);
        int legacyIter = staticIntField("LEGACY_ITER");
        char[] password = "correct horse".toCharArray();

        byte[] ciphertext = encryptZip(trivialZip(), password.clone(), salt, legacyIter, iv);
        byte[] bundle = buildV1Bundle(salt, iv, ciphertext);
        Path tmp = writeTemp(bundle);
        try {
            BackupBundle.ImportResult result = assertDoesNotThrow(
                    () -> BackupBundle.importBundle(tmp, password.clone()),
                    "a legacy v1 bundle must still import with its correct password");
            assertEquals(1, result.sessions().size());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void importBundle_wrongPasswordOnV3Bundle_throwsCleanError() throws Exception {
        char[] realPassword = "right-pw".toCharArray();
        byte[] bundleBytes = encrypt(trivialZip(), realPassword.clone());
        Path tmp = writeTemp(bundleBytes);
        try {
            Exception ex = assertThrows(Exception.class,
                    () -> BackupBundle.importBundle(tmp, "wrong-pw".toCharArray()));
            assertTrue(ex instanceof java.io.IOException, "got: " + ex.getClass());
            assertTrue(ex.getMessage().contains("Wrong password or corrupt backup file"), "got: " + ex.getMessage());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
