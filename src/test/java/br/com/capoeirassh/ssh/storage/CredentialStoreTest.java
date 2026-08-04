package br.com.capoeirassh.ssh.storage;

import br.com.capoeirassh.ssh.model.CredentialEntry;
import org.junit.jupiter.api.*;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link CredentialStore}, including {@code unlock()}'s file-format validation and
 * v1-&gt;v2 migration, against a REDIRECTED vault path rather than the real
 * {@code ~/.capoeira/credentials.vault}.
 *
 * Safety mechanism: the surefire plugin (see pom.xml) sets {@code user.home} to
 * {@code target/test-home} for the forked JVM that runs tests, and {@code CredentialStore.VAULT}
 * is a {@code static final Path} resolved from {@code user.home} once at class-load time — so by
 * the time this class (or CredentialStore) is ever loaded, the vault path already points under
 * {@code target/}, never at the real home directory. {@link #verifyVaultIsRedirected()} asserts
 * that redirection actually took effect, and aborts the whole class if it didn't, rather than
 * silently falling through to touching real user data.
 */
class CredentialStoreTest {

    private static Path vaultPath;

    @BeforeAll
    static void verifyVaultIsRedirected() throws Exception {
        Field f = CredentialStore.class.getDeclaredField("VAULT");
        f.setAccessible(true);
        vaultPath = (Path) f.get(null);
        String home = System.getProperty("user.home");
        Assumptions.assumeTrue(home != null && home.contains("test-home"),
                "user.home was not redirected to a test directory (got: " + home + ") — "
              + "refusing to run tests that write to CredentialStore's vault path, "
              + "since it may resolve to the real ~/.capoeira/credentials.vault. "
              + "Run via `mvn test` (surefire applies the redirect); running this class "
              + "directly from an IDE without that JVM argument will hit this guard.");
        assertTrue(vaultPath.toString().contains("test-home"),
                "sanity check on the reflected VAULT path itself: " + vaultPath);
    }

    @BeforeEach
    void cleanVault() throws Exception {
        Files.createDirectories(vaultPath.getParent());
        Files.deleteIfExists(vaultPath);
        if (CredentialStore.getInstance().isUnlocked()) CredentialStore.getInstance().lock();
    }

    @AfterEach
    void lockAndClean() throws Exception {
        if (CredentialStore.getInstance().isUnlocked()) CredentialStore.getInstance().lock();
        Files.deleteIfExists(vaultPath);
    }

    // -----------------------------------------------------------------------
    // Reflection plumbing — private static members exercised directly, stateless with respect
    // to the singleton (except deriveKeyBytes/serialize/deserialize, which are pure functions).
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<CredentialEntry> deserialize(char[] chars) throws Exception {
        Method m = CredentialStore.class.getDeclaredMethod("deserialize", char[].class);
        m.setAccessible(true);
        return (List<CredentialEntry>) m.invoke(null, (Object) chars);
    }

    private static char[] serializeToChars(List<CredentialEntry> list) throws Exception {
        Method m = CredentialStore.class.getDeclaredMethod("serialize", List.class);
        m.setAccessible(true);
        StringBuilder sb = (StringBuilder) m.invoke(null, list);
        char[] out = new char[sb.length()];
        sb.getChars(0, sb.length(), out, 0);
        return out;
    }

    private static byte[] deriveKeyBytes(char[] password, byte[] salt, int iterations) throws Exception {
        Method m = CredentialStore.class.getDeclaredMethod("deriveKeyBytes", char[].class, byte[].class, int.class);
        m.setAccessible(true);
        try {
            return (byte[]) m.invoke(null, password, salt, iterations);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception ex) throw ex;
            throw e;
        }
    }

    private static <T> T staticField(String name) throws Exception {
        Field f = CredentialStore.class.getDeclaredField(name);
        f.setAccessible(true);
        @SuppressWarnings("unchecked") T val = (T) f.get(null);
        return val;
    }

    private static CredentialEntry entry(String label, String username, String keyPath, char[] password) {
        CredentialEntry e = new CredentialEntry();
        e.label    = label;
        e.username = username;
        e.keyPath  = keyPath;
        e.password = password;
        return e;
    }

    /** AES/GCM-encrypts the serialized form of {@code entries} with a key derived from
     *  {@code password}/{@code salt}/{@code iterations}, mirroring exactly what persist() does. */
    private static byte[] encryptEntries(List<CredentialEntry> entries, char[] password, byte[] salt,
                                          int iterations, byte[] iv) throws Exception {
        byte[] keyBytes = deriveKeyBytes(password, salt, iterations);
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(128, iv));
        char[] plainChars = serializeToChars(entries);
        byte[] plainBytes = new String(plainChars).getBytes(StandardCharsets.UTF_8);
        return aes.doFinal(plainBytes);
    }

    /** Hand-assembles a v1-format vault file: magic + version(1) + salt + iv + ciphertext. */
    private static byte[] buildV1Vault(byte[] salt, byte[] iv, byte[] ciphertext) throws Exception {
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

    /** Hand-assembles a v2-format vault file: magic + version(2) + kdfId + iterations(4 BE) +
     *  salt + iv + ciphertext — same layout persist() writes. */
    private static byte[] buildV2Vault(int kdfId, int iterations, byte[] salt, byte[] iv, byte[] ciphertext)
            throws Exception {
        byte[] magic = staticField("MAGIC");
        byte[] out = new byte[magic.length + 1 + 1 + 4 + salt.length + iv.length + ciphertext.length];
        int off = 0;
        System.arraycopy(magic, 0, out, off, magic.length); off += magic.length;
        out[off++] = 2; // version
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

    // -----------------------------------------------------------------------
    // Round-trip serialization
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("round-trip: password containing backslash")
    void roundTrip_backslashInPassword() throws Exception {
        CredentialEntry original = entry("srv", "root", "", "p\\a\\ss".toCharArray());
        List<CredentialEntry> back = deserialize(serializeToChars(List.of(original)));
        assertEquals(1, back.size());
        assertArrayEquals(original.password, back.get(0).password);
    }

    @Test
    @DisplayName("round-trip: password containing '='")
    void roundTrip_equalsInPassword() throws Exception {
        CredentialEntry original = entry("srv", "root", "", "a=b=c".toCharArray());
        List<CredentialEntry> back = deserialize(serializeToChars(List.of(original)));
        assertArrayEquals(original.password, back.get(0).password);
    }

    @Test
    @DisplayName("round-trip: password containing a literal newline")
    void roundTrip_newlineInPassword() throws Exception {
        CredentialEntry original = entry("srv", "root", "", "line1\nline2".toCharArray());
        List<CredentialEntry> back = deserialize(serializeToChars(List.of(original)));
        assertArrayEquals(original.password, back.get(0).password);
    }

    @Test
    @DisplayName("round-trip: password with backslash+newline+equals combined, adversarial order")
    void roundTrip_combinedEscapes() throws Exception {
        // Deliberately arranged so a naive chained-String.replace() unescape would mis-decode it
        // (e.g. a literal "\\" followed by "n" must NOT become a newline).
        CredentialEntry original = entry("srv", "root", "", "\\n=\\\\n\\=end".toCharArray());
        List<CredentialEntry> back = deserialize(serializeToChars(List.of(original)));
        assertArrayEquals(original.password, back.get(0).password);
    }

    @Test
    @DisplayName("round-trip: unicode outside the BMP (surrogate pair)")
    void roundTrip_nonBmpUnicode() throws Exception {
        // U+1F600 (GRINNING FACE), encoded as a UTF-16 surrogate pair \uD83D\uDE00 — a code point
        // outside the Basic Multilingual Plane, which takes two chars in a Java char[].
        char[] pw = ("s3cr" + "\uD83D\uDE00" + "t").toCharArray();
        assertEquals(7, pw.length, "sanity: 4 + 2 (surrogate pair) + 1 chars");
        CredentialEntry original = entry("srv", "root", "", pw);
        List<CredentialEntry> back = deserialize(serializeToChars(List.of(original)));
        assertArrayEquals(original.password, back.get(0).password);
    }

    @Test
    @DisplayName("round-trip: empty-string password")
    void roundTrip_emptyPassword() throws Exception {
        CredentialEntry original = entry("srv", "root", "", new char[0]);
        List<CredentialEntry> back = deserialize(serializeToChars(List.of(original)));
        assertEquals(1, back.size());
        assertEquals(0, back.get(0).password.length);
    }

    @Test
    @DisplayName("round-trip: label/username/keyPath also survive escaping (=, \\n, \\)")
    void roundTrip_otherFieldsEscaped() throws Exception {
        CredentialEntry original = entry("label=with=equals", "user\\name", "C:\\keys\\id_rsa\n.pem",
                "pw".toCharArray());
        List<CredentialEntry> back = deserialize(serializeToChars(List.of(original)));
        CredentialEntry got = back.get(0);
        assertEquals(original.label, got.label);
        assertEquals(original.username, got.username);
        assertEquals(original.keyPath, got.keyPath);
    }

    @Test
    @DisplayName("round-trip: multiple entries preserve identity and order-independent lookup")
    void roundTrip_multipleEntries() throws Exception {
        CredentialEntry a = entry("A", "u1", "", "pw1".toCharArray());
        CredentialEntry b = entry("B", "u2", "", "pw2".toCharArray());
        List<CredentialEntry> back = deserialize(serializeToChars(List.of(a, b)));
        assertEquals(2, back.size());
        assertTrue(back.stream().anyMatch(e -> e.id.equals(a.id) && new String(e.password).equals("pw1")));
        assertTrue(back.stream().anyMatch(e -> e.id.equals(b.id) && new String(e.password).equals("pw2")));
    }

    // -----------------------------------------------------------------------
    // Crypto: wrong password -> AEADBadTagException (in isolation, no file I/O)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("wrong master password on decrypt throws AEADBadTagException")
    void wrongPassword_throwsAEADBadTagException() throws Exception {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);

        byte[] rightKey = deriveKeyBytes("correct horse".toCharArray(), salt, 10_000);
        byte[] wrongKey = deriveKeyBytes("wrong password".toCharArray(), salt, 10_000);

        Cipher enc = Cipher.getInstance("AES/GCM/NoPadding");
        enc.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(rightKey, "AES"), new GCMParameterSpec(128, iv));
        byte[] ciphertext = enc.doFinal("plaintext payload".getBytes(StandardCharsets.UTF_8));

        Cipher dec = Cipher.getInstance("AES/GCM/NoPadding");
        dec.init(Cipher.DECRYPT_MODE, new SecretKeySpec(wrongKey, "AES"), new GCMParameterSpec(128, iv));
        assertThrows(AEADBadTagException.class, () -> dec.doFinal(ciphertext));
    }

    @Test
    @DisplayName("correct master password decrypts successfully (control case for the above)")
    void correctPassword_decryptsSuccessfully() throws Exception {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);

        byte[] key = deriveKeyBytes("correct horse".toCharArray(), salt, 10_000);

        Cipher enc = Cipher.getInstance("AES/GCM/NoPadding");
        enc.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] ciphertext = enc.doFinal("plaintext payload".getBytes(StandardCharsets.UTF_8));

        Cipher dec = Cipher.getInstance("AES/GCM/NoPadding");
        dec.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] plain = dec.doFinal(ciphertext);
        assertEquals("plaintext payload", new String(plain, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("wrong master password via the real unlock() path throws AEADBadTagException")
    void unlock_wrongPassword_throwsAEADBadTagException() throws Exception {
        CredentialStore store = CredentialStore.getInstance();
        store.create("right-password".toCharArray());
        store.lock();
        assertThrows(AEADBadTagException.class, () -> store.unlock("wrong-password".toCharArray()));
    }

    // -----------------------------------------------------------------------
    // unlock(): file-format validation, against the redirected vault path
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("unlock() rejects a truncated file")
    void unlock_rejectsTruncatedFile() throws Exception {
        // Well-formed magic + version, but far short of magic+version+salt+iv+tag.
        byte[] magic = staticField("MAGIC");
        byte[] truncated = new byte[magic.length + 1 + 3]; // way under the minimum
        System.arraycopy(magic, 0, truncated, 0, magic.length);
        truncated[magic.length] = 1; // version 1
        Files.write(vaultPath, truncated);

        Exception ex = assertThrows(Exception.class,
                () -> CredentialStore.getInstance().unlock("whatever".toCharArray()));
        assertFalse(ex instanceof AEADBadTagException, "must be rejected before any crypto is attempted");
        assertTrue(ex.getMessage().contains("Not a Capoeira vault file"), "got: " + ex.getMessage());
    }

    @Test
    @DisplayName("unlock() rejects wrong magic bytes")
    void unlock_rejectsWrongMagic() throws Exception {
        byte[] salt = new byte[(int) (int) staticField_int("SALT_LEN")];
        byte[] iv   = new byte[(int) staticField_int("IV_LEN")];
        new SecureRandom().nextBytes(salt);
        new SecureRandom().nextBytes(iv);
        byte[] ciphertext = encryptEntries(List.of(), "pw".toCharArray(), salt, 120_000, iv);
        byte[] good = buildV1Vault(salt, iv, ciphertext);
        byte[] bad = good.clone();
        bad[0] = (byte) (bad[0] ^ 0xFF); // corrupt the first magic byte
        Files.write(vaultPath, bad);

        Exception ex = assertThrows(Exception.class,
                () -> CredentialStore.getInstance().unlock("pw".toCharArray()));
        assertTrue(ex.getMessage().contains("Not a Capoeira vault file"), "got: " + ex.getMessage());
    }

    @Test
    @DisplayName("unlock() rejects an unknown version byte")
    void unlock_rejectsUnknownVersion() throws Exception {
        byte[] magic = staticField("MAGIC");
        byte[] raw = new byte[magic.length + 1];
        System.arraycopy(magic, 0, raw, 0, magic.length);
        raw[magic.length] = 99; // unsupported version
        Files.write(vaultPath, raw);

        Exception ex = assertThrows(Exception.class,
                () -> CredentialStore.getInstance().unlock("whatever".toCharArray()));
        assertTrue(ex.getMessage().contains("Unsupported vault version"), "got: " + ex.getMessage());
    }

    @Test
    @DisplayName("unlock() rejects a v2 header with an out-of-range KDF iteration count")
    void unlock_rejectsIterationOutOfRange() throws Exception {
        int saltLen = staticField_int("SALT_LEN");
        int ivLen   = staticField_int("IV_LEN");
        int kdfPbkdf2 = staticField_int("KDF_PBKDF2");
        byte[] salt = new byte[saltLen], iv = new byte[ivLen];
        new SecureRandom().nextBytes(salt);
        new SecureRandom().nextBytes(iv);

        // A real ciphertext isn't needed — the iteration-count guard rejects before any
        // decryption is attempted, so garbage ciphertext bytes are fine here.
        byte[] fakeCiphertext = new byte[32];
        new SecureRandom().nextBytes(fakeCiphertext);

        byte[] tooFew = buildV2Vault(kdfPbkdf2, 999, salt, iv, fakeCiphertext);
        Files.write(vaultPath, tooFew);
        Exception ex1 = assertThrows(Exception.class,
                () -> CredentialStore.getInstance().unlock("whatever".toCharArray()));
        assertTrue(ex1.getMessage().contains("Invalid vault KDF iteration count"), "got: " + ex1.getMessage());

        byte[] tooMany = buildV2Vault(kdfPbkdf2, 3_000_000, salt, iv, fakeCiphertext);
        Files.write(vaultPath, tooMany);
        Exception ex2 = assertThrows(Exception.class,
                () -> CredentialStore.getInstance().unlock("whatever".toCharArray()));
        assertTrue(ex2.getMessage().contains("Invalid vault KDF iteration count"), "got: " + ex2.getMessage());
    }

    private static int staticField_int(String name) throws Exception { return staticField(name); }

    // -----------------------------------------------------------------------
    // v1 -> v2 migration
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("unlock() migrates a v1 vault to v2 in place, preserving every entry")
    void unlock_migratesV1ToV2PreservingEntries() throws Exception {
        char[] password = "correct horse battery staple".toCharArray();
        int legacyIter = staticField_int("LEGACY_ITER");
        int saltLen = staticField_int("SALT_LEN");
        int ivLen   = staticField_int("IV_LEN");

        byte[] salt = new byte[saltLen], iv = new byte[ivLen];
        new SecureRandom().nextBytes(salt);
        new SecureRandom().nextBytes(iv);

        List<CredentialEntry> original = List.of(
                entry("Prod DB", "admin", "", "s3cr3t!".toCharArray()),
                entry("Backup host", "opc", "/home/opc/.ssh/id_rsa", "".toCharArray()),
                entry("Weird=chars\\here", "u", "", "p=a\\ss\nw0rd".toCharArray())
        );

        byte[] ciphertext = encryptEntries(original, password.clone(), salt, legacyIter, iv);
        byte[] v1File = buildV1Vault(salt, iv, ciphertext);
        Files.write(vaultPath, v1File);

        // Sanity: confirm the file we wrote really is tagged v1 before unlocking it.
        byte[] beforeRaw = Files.readAllBytes(vaultPath);
        int versionOffset = staticField_int_magicLen();
        assertEquals(1, beforeRaw[versionOffset] & 0xFF, "test setup: file must start as v1");

        CredentialStore store = CredentialStore.getInstance();
        store.unlock(password.clone());

        List<CredentialEntry> loaded = store.getAll();
        assertEquals(original.size(), loaded.size());
        for (CredentialEntry o : original) {
            CredentialEntry match = loaded.stream().filter(e -> e.label.equals(o.label)).findFirst()
                    .orElseThrow(() -> new AssertionError("missing entry: " + o.label));
            assertEquals(o.username, match.username);
            assertEquals(o.keyPath, match.keyPath);
            assertArrayEquals(o.password, match.password);
        }

        // The file on disk must now be v2 — migration happened, not just an in-memory load.
        byte[] afterRaw = Files.readAllBytes(vaultPath);
        assertEquals(2, afterRaw[versionOffset] & 0xFF, "vault file should have been migrated to v2 on disk");

        // And the migrated file must itself be independently unlockable with the same password.
        store.lock();
        assertDoesNotThrow(() -> store.unlock(password.clone()));
        assertEquals(original.size(), store.getAll().size());
    }

    private static int staticField_int_magicLen() throws Exception {
        return ((byte[]) staticField("MAGIC")).length;
    }

    // -----------------------------------------------------------------------
    // Auto-lock: create() must touch() so a freshly created, never-accessed vault is still
    // eligible for the inactivity timer instead of sitting unlocked forever.
    // -----------------------------------------------------------------------

    private static long instanceLastAccessMs(CredentialStore store) throws Exception {
        Field f = CredentialStore.class.getDeclaredField("lastAccessMs");
        f.setAccessible(true);
        return f.getLong(store);
    }

    @Test
    @DisplayName("create() touches lastAccessMs so the auto-lock timer's lastAccessMs>0 guard doesn't skip a freshly-created vault forever")
    void create_touchesLastAccessMs() throws Exception {
        CredentialStore store = CredentialStore.getInstance();
        store.create("master-password".toCharArray());

        long lastAccessMs = instanceLastAccessMs(store);
        assertTrue(lastAccessMs > 0,
                "lastAccessMs was " + lastAccessMs + " after create() — the auto-lock timer only "
              + "fires when lastAccessMs > 0, so a vault that is created and never subsequently "
              + "read/written (no getAll/addOrUpdate/etc. call) would stay unlocked forever, no "
              + "matter how long it sits idle");
    }
}
