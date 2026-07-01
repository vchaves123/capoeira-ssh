package br.com.quatorzebis.ssh.storage;

import br.com.quatorzebis.ssh.model.CredentialEntry;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.*;

/**
 * Encrypted credential vault using AES-256-GCM.
 *
 * File layout (~/.14bis/credentials.vault):
 *   [4]  magic  0x444D534C ("DMSL")
 *   [1]  version (1)
 *   [16] PBKDF2 salt
 *   [12] AES-GCM IV
 *   [N]  GCM ciphertext + 16-byte auth tag
 */
public final class CredentialStore {

    private static final CredentialStore INSTANCE = new CredentialStore();

    private static final Path   VAULT   = Path.of(System.getProperty("user.home"), ".14bis", "credentials.vault");
    private static final byte[] MAGIC   = {0x44, 0x4D, 0x53, 0x4C};
    private static final int    VERSION = 1;
    private static final int    SALT_LEN = 16;
    private static final int    IV_LEN   = 12;
    private static final int    PBKDF2_ITER = 120_000;

    private List<CredentialEntry> entries   = new ArrayList<>();
    private SecretKey             masterKey = null;
    private byte[]                salt      = null;

    private CredentialStore() {}

    public static CredentialStore getInstance() { return INSTANCE; }

    public boolean isUnlocked()   { return masterKey != null; }
    public boolean vaultExists()  { return Files.exists(VAULT); }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /** Create a brand-new vault with the given master password. Zeroes the array after use. */
    public void create(char[] masterPassword) throws Exception {
        this.salt      = randomBytes(SALT_LEN);
        this.masterKey = deriveKey(masterPassword, salt);
        Arrays.fill(masterPassword, '\0');
        this.entries   = new ArrayList<>();
        persist();
    }

    /**
     * Unlock an existing vault. Zeroes the array after use.
     * @throws AEADBadTagException if the master password is wrong.
     */
    public void unlock(char[] masterPassword) throws Exception {
        byte[] raw  = Files.readAllBytes(VAULT);
        int    off  = 0;

        for (byte b : MAGIC) {
            if (raw[off++] != b) throw new Exception("Not a 14bis vault file.");
        }
        int ver = raw[off++] & 0xFF;
        if (ver != VERSION) throw new Exception("Unsupported vault version: " + ver);

        byte[] fileSalt = Arrays.copyOfRange(raw, off, off + SALT_LEN); off += SALT_LEN;
        byte[] iv       = Arrays.copyOfRange(raw, off, off + IV_LEN);   off += IV_LEN;
        byte[] cipher   = Arrays.copyOfRange(raw, off, raw.length);

        SecretKey key = deriveKey(masterPassword, fileSalt);
        Arrays.fill(masterPassword, '\0');
        Cipher aes    = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] plain  = aes.doFinal(cipher);   // throws AEADBadTagException on wrong key

        this.salt      = fileSalt;
        this.masterKey = key;
        String plainText = new String(plain, StandardCharsets.UTF_8);
        Arrays.fill(plain, (byte) 0);
        this.entries   = deserialize(plainText);
    }

    public void lock() {
        masterKey = null;
        entries   = new ArrayList<>();
        salt      = null;
    }

    // -----------------------------------------------------------------------
    // CRUD
    // -----------------------------------------------------------------------

    public List<CredentialEntry> getAll() {
        return Collections.unmodifiableList(entries);
    }

    public CredentialEntry findById(String id) {
        if (id == null || id.isBlank()) return null;
        return entries.stream().filter(e -> e.id.equals(id)).findFirst().orElse(null);
    }

    public void addOrUpdate(CredentialEntry e) throws Exception {
        entries.removeIf(x -> x.id.equals(e.id));
        entries.add(e);
        persist();
    }

    public void delete(String id) throws Exception {
        entries.removeIf(e -> e.id.equals(id));
        persist();
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    private void persist() throws Exception {
        if (masterKey == null) throw new IllegalStateException("Vault is locked.");
        byte[] iv    = randomBytes(IV_LEN);
        Cipher aes   = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(128, iv));
        byte[] ciph  = aes.doFinal(serialize(entries).getBytes(StandardCharsets.UTF_8));

        byte[] out = new byte[4 + 1 + SALT_LEN + IV_LEN + ciph.length];
        int off = 0;
        System.arraycopy(MAGIC,  0, out, off, 4);       off += 4;
        out[off++] = (byte) VERSION;
        System.arraycopy(salt,   0, out, off, SALT_LEN); off += SALT_LEN;
        System.arraycopy(iv,     0, out, off, IV_LEN);   off += IV_LEN;
        System.arraycopy(ciph,   0, out, off, ciph.length);

        SecureFiles.write(VAULT, out);
    }

    // -----------------------------------------------------------------------
    // Serialization (plaintext inside the vault)
    // -----------------------------------------------------------------------

    private static String serialize(List<CredentialEntry> list) {
        StringBuilder sb = new StringBuilder();
        for (CredentialEntry e : list) {
            sb.append("e.").append(e.id).append(".l=").append(esc(e.label))   .append('\n');
            sb.append("e.").append(e.id).append(".u=").append(esc(e.username)).append('\n');
            sb.append("e.").append(e.id).append(".p=");
            escChars(e.password, sb);
            sb.append('\n');
        }
        return sb.toString();
    }

    private static List<CredentialEntry> deserialize(String text) {
        Map<String, CredentialEntry> map = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq);
            String val = unesc(line.substring(eq + 1));
            String[] p = key.split("\\.", 3);          // ["e", id, field]
            if (p.length != 3 || !"e".equals(p[0])) continue;
            CredentialEntry ce = map.computeIfAbsent(p[1], id -> {
                CredentialEntry x = new CredentialEntry(); x.id = id; return x;
            });
            switch (p[2]) {
                case "l" -> ce.label    = val;
                case "u" -> ce.username = val;
                case "p" -> ce.password = val.toCharArray();
            }
        }
        return new ArrayList<>(map.values());
    }

    /** Escape a String value for vault serialization. Order is critical: backslash first. */
    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("=", "\\=");
    }

    /**
     * Escape a char[] password directly into sb — avoids creating an intermediate String.
     * Same escaping rules as esc(): backslash first, then newline, then equals.
     */
    private static void escChars(char[] chars, StringBuilder sb) {
        for (char c : chars) {
            if      (c == '\\') { sb.append('\\'); sb.append('\\'); }
            else if (c == '\n') { sb.append('\\'); sb.append('n');  }
            else if (c == '=')  { sb.append('\\'); sb.append('=');  }
            else                { sb.append(c); }
        }
    }

    /**
     * Unescape a vault-serialized value. Order is critical — must mirror esc() in reverse:
     * \= → = first, then \n → newline, then \\ → \ last.
     * Reversing this order would corrupt values containing literal backslash+equals.
     */
    private static String unesc(String s) {
        return s.replace("\\=", "=").replace("\\n", "\n").replace("\\\\", "\\");
    }

    // -----------------------------------------------------------------------
    // Crypto helpers
    // -----------------------------------------------------------------------

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static SecretKey deriveKey(char[] password, byte[] salt) throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITER, 256);
        try {
            return new SecretKeySpec(f.generateSecret(spec).getEncoded(), "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] randomBytes(int len) {
        byte[] b = new byte[len];
        SECURE_RANDOM.nextBytes(b);
        return b;
    }
}
