package br.com.capoeirassh.ssh.storage;

import br.com.capoeirassh.ssh.model.CredentialEntry;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.*;

/**
 * Encrypted credential vault using AES-256-GCM.
 *
 * File layout (~/.capoeira/credentials.vault):
 *   [4]  magic  0x444D534C ("DMSL")
 *   [1]  version (1)
 *   [16] PBKDF2 salt
 *   [12] AES-GCM IV
 *   [N]  GCM ciphertext + 16-byte auth tag
 */
public final class CredentialStore {

    private static final CredentialStore INSTANCE = new CredentialStore();

    private static final Path   VAULT   = Path.of(System.getProperty("user.home"), ".capoeira", "credentials.vault");
    private static final byte[] MAGIC   = {0x44, 0x4D, 0x53, 0x4C};
    private static final int    VERSION = 2;            // v2 header self-describes KDF params
    private static final int    SALT_LEN = 16;
    private static final int    IV_LEN   = 12;
    private static final int    KDF_PBKDF2   = 1;        // KDF-algo id stored in the v2 header
    private static final int    LEGACY_ITER  = 120_000;  // v1 files: iteration count was implicit
    private static final int    CURRENT_ITER = 600_000;  // OWASP-2023 baseline; new/migrated files

    private static final long INACTIVITY_MS = 5 * 60 * 1000L; // 5 minutes

    private List<CredentialEntry> entries   = new ArrayList<>();
    private SecretKey             masterKey = null;
    private byte[]                salt      = null;
    private int                   iterations = LEGACY_ITER; // KDF iterations of the loaded key

    private volatile long    lastAccessMs  = 0;
    private volatile Runnable onLockCallback = null;
    private final ScheduledExecutorService lockTimer =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "vault-autolock");
                t.setDaemon(true);
                return t;
            });

    private CredentialStore() {
        lockTimer.scheduleAtFixedRate(() -> {
            boolean locked = false;
            synchronized (this) {
                if (masterKey != null && lastAccessMs > 0
                        && System.currentTimeMillis() - lastAccessMs >= INACTIVITY_MS) {
                    lock();
                    locked = true;
                }
            }
            // Fire the callback outside the monitor — it may hop to the UI thread.
            if (locked) {
                Runnable cb = onLockCallback;
                if (cb != null) cb.run();
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * Register a callback invoked whenever the vault changes lock state
     * (auto-lock from background thread, or unlock from any thread).
     * The callback may be called from a non-UI thread — use asyncExec if updating SWT widgets.
     */
    public void setOnLockCallback(Runnable callback) { this.onLockCallback = callback; }

    private void touch() { lastAccessMs = System.currentTimeMillis(); }

    public static CredentialStore getInstance() { return INSTANCE; }

    public synchronized boolean isUnlocked()   { return masterKey != null; }
    public boolean vaultExists()  { return Files.exists(VAULT); }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /** Create a brand-new vault with the given master password. Zeroes the array after use. */
    public synchronized void create(char[] masterPassword) throws Exception {
        this.salt       = randomBytes(SALT_LEN);
        this.iterations = CURRENT_ITER;
        this.masterKey  = deriveKey(masterPassword, salt, CURRENT_ITER);
        Arrays.fill(masterPassword, '\0');
        this.entries    = new ArrayList<>();
        persist();
    }

    /**
     * Unlock an existing vault. Zeroes the array after use.
     * @throws AEADBadTagException if the master password is wrong.
     */
    public synchronized void unlock(char[] masterPassword) throws Exception {
        try {
            byte[] raw = Files.readAllBytes(VAULT);
            if (raw.length < 5) throw new Exception("Not a Capoeira vault file.");
            int off = 0;
            for (byte b : MAGIC) {
                if (raw[off++] != b) throw new Exception("Not a Capoeira vault file.");
            }
            int ver = raw[off++] & 0xFF;
            int iter;
            if (ver == 1) {                       // legacy: KDF params were implicit
                iter = LEGACY_ITER;
            } else if (ver == 2) {                // self-describing header
                if (raw.length < 4 + 1 + 1 + 4 + SALT_LEN + IV_LEN + 16)
                    throw new Exception("Not a Capoeira vault file.");
                int kdfId = raw[off++] & 0xFF;
                iter = ((raw[off] & 0xFF) << 24) | ((raw[off + 1] & 0xFF) << 16)
                     | ((raw[off + 2] & 0xFF) << 8) | (raw[off + 3] & 0xFF);
                off += 4;
                if (kdfId != KDF_PBKDF2) throw new Exception("Unsupported KDF id: " + kdfId);
                // Reject an absurd iteration count from an untrusted header — otherwise a
                // crafted vault file can peg a CPU core for minutes deriving a key before the
                // (attacker-controlled) file is even confirmed as wrong.
                if (iter < 1_000 || iter > 2_000_000)
                    throw new Exception("Invalid vault KDF iteration count: " + iter);
            } else {
                throw new Exception("Unsupported vault version: " + ver);
            }

            byte[] fileSalt = Arrays.copyOfRange(raw, off, off + SALT_LEN); off += SALT_LEN;
            byte[] iv       = Arrays.copyOfRange(raw, off, off + IV_LEN);   off += IV_LEN;
            byte[] cipher   = Arrays.copyOfRange(raw, off, raw.length);

            SecretKey key = deriveKey(masterPassword, fileSalt, iter);
            Cipher aes    = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] plain  = aes.doFinal(cipher);   // throws AEADBadTagException on wrong key

            this.salt       = fileSalt;
            this.iterations = iter;
            this.masterKey  = key;
            touch();
            // Decode straight to char[] — never materialize the whole plaintext vault
            // (every saved password) as an immutable String, which can't be zeroed
            // and would otherwise linger on the heap until GC.
            char[] plainChars = bytesToChars(plain);
            Arrays.fill(plain, (byte) 0);
            try {
                this.entries = deserialize(plainChars);
            } finally {
                Arrays.fill(plainChars, '\0');
            }

            // Transparently upgrade legacy / weaker-KDF vaults to CURRENT_ITER (v2) with a
            // fresh salt — the password is still in hand here. Best-effort: a read-only vault
            // dir must never block a successful unlock.
            if (iter < CURRENT_ITER) {
                try {
                    byte[] newSalt = randomBytes(SALT_LEN);
                    this.salt       = newSalt;
                    this.iterations = CURRENT_ITER;
                    this.masterKey  = deriveKey(masterPassword, newSalt, CURRENT_ITER);
                    persist();
                } catch (Exception rePersistFailed) {
                    // Revert to the working key/salt so in-memory state matches what's on disk.
                    this.salt       = fileSalt;
                    this.iterations = iter;
                    this.masterKey  = key;
                }
            }
        } finally {
            Arrays.fill(masterPassword, '\0');     // always wipe, even on the wrong-password path
        }
        Runnable cb = onLockCallback;
        if (cb != null) cb.run();
    }

    public synchronized void lock() {
        // Zero cached secrets before dropping them so no plaintext password lingers
        // on the heap after the vault is locked (defends against a later heap dump).
        for (CredentialEntry e : entries) {
            if (e.password != null) Arrays.fill(e.password, '\0');
        }
        masterKey = null;
        entries   = new ArrayList<>();
        salt      = null;
    }

    // -----------------------------------------------------------------------
    // CRUD
    // -----------------------------------------------------------------------

    public synchronized List<CredentialEntry> getAll() {
        touch();
        return List.copyOf(entries);
    }

    public synchronized CredentialEntry findById(String id) {
        if (id == null || id.isBlank()) return null;
        touch();
        return entries.stream().filter(e -> e.id.equals(id)).findFirst().orElse(null);
    }

    public synchronized void addOrUpdate(CredentialEntry e) throws Exception {
        touch();
        entries.removeIf(x -> x.id.equals(e.id));
        entries.add(e);
        persist();
    }

    public synchronized void delete(String id) throws Exception {
        touch();
        entries.removeIf(e -> e.id.equals(id));
        persist();
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    private synchronized void persist() throws Exception {
        if (masterKey == null) throw new IllegalStateException("Vault is locked.");
        byte[] iv    = randomBytes(IV_LEN);
        Cipher aes   = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(128, iv));

        // Serialize without ever forming one immutable String holding the whole
        // plaintext vault (every saved password) — extract into a char[]/byte[]
        // we can explicitly zero once the ciphertext has been produced.
        StringBuilder sb = serialize(entries);
        char[] plainChars = new char[sb.length()];
        sb.getChars(0, sb.length(), plainChars, 0);
        wipe(sb);
        byte[] plainBytes = charsToBytes(plainChars);
        Arrays.fill(plainChars, '\0');
        byte[] ciph;
        try {
            ciph = aes.doFinal(plainBytes);
        } finally {
            Arrays.fill(plainBytes, (byte) 0);
        }

        byte[] out = new byte[4 + 1 + 1 + 4 + SALT_LEN + IV_LEN + ciph.length];
        int off = 0;
        System.arraycopy(MAGIC,  0, out, off, 4);       off += 4;
        out[off++] = (byte) VERSION;                     // 2
        out[off++] = (byte) KDF_PBKDF2;                  // KDF-algo id
        int it = this.iterations;
        out[off++] = (byte) (it >>> 24); out[off++] = (byte) (it >>> 16);
        out[off++] = (byte) (it >>> 8);  out[off++] = (byte) it;
        System.arraycopy(salt,   0, out, off, SALT_LEN); off += SALT_LEN;
        System.arraycopy(iv,     0, out, off, IV_LEN);   off += IV_LEN;
        System.arraycopy(ciph,   0, out, off, ciph.length);

        SecureFiles.write(VAULT, out);
    }

    // -----------------------------------------------------------------------
    // Serialization (plaintext inside the vault)
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // Package-private API used by BackupBundle
    // -----------------------------------------------------------------------

    /**
     * Merge incoming credential entries into the unlocked vault.
     * Entries whose label already exists get a " (imported)" suffix and a new ID.
     *
     * @return map of original ID → final ID (same when no conflict, new UUID on conflict)
     */
    public synchronized Map<String, String> mergeCredentials(List<br.com.capoeirassh.ssh.model.CredentialEntry> incoming)
            throws Exception {
        if (masterKey == null) throw new IllegalStateException("Vault is locked.");
        Set<String> usedLabels = entries.stream()
                .map(e -> e.label.toLowerCase())
                .collect(Collectors.toCollection(java.util.HashSet::new));

        Map<String, String> remap = new java.util.LinkedHashMap<>();
        for (br.com.capoeirassh.ssh.model.CredentialEntry imp : incoming) {
            String origId = imp.id;
            imp.id = UUID.randomUUID().toString();
            remap.put(origId, imp.id);
            if (usedLabels.contains(imp.label.toLowerCase())) {
                imp.label = imp.label + " (imported)";
            }
            usedLabels.add(imp.label.toLowerCase());
            entries.add(imp);
        }
        persist();
        return remap;
    }

    /** Serialize entries to a char[] suitable for embedding in a backup bundle. */
    static char[] exportEntries(List<br.com.capoeirassh.ssh.model.CredentialEntry> list) {
        StringBuilder sb = serialize(list);
        char[] out = new char[sb.length()];
        sb.getChars(0, sb.length(), out, 0);
        wipe(sb);
        return out;
    }

    /** Parse entries from a char[] previously produced by exportEntries(). */
    static List<br.com.capoeirassh.ssh.model.CredentialEntry> parseEntries(char[] chars) {
        return deserialize(chars);
    }

    // -----------------------------------------------------------------------
    // Serialization (plaintext inside the vault)
    // -----------------------------------------------------------------------

    private static StringBuilder serialize(List<CredentialEntry> list) {
        StringBuilder sb = new StringBuilder();
        for (CredentialEntry e : list) {
            sb.append("e.").append(e.id).append(".l=").append(esc(e.label))   .append('\n');
            sb.append("e.").append(e.id).append(".u=").append(esc(e.username)).append('\n');
            sb.append("e.").append(e.id).append(".k=").append(esc(e.keyPath != null ? e.keyPath : "")).append('\n');
            sb.append("e.").append(e.id).append(".p=");
            escChars(e.password, sb);
            sb.append('\n');
        }
        return sb;
    }

    /** Overwrites a StringBuilder's contents in place so no plaintext copy lingers in its backing array. */
    private static void wipe(StringBuilder sb) {
        for (int i = 0; i < sb.length(); i++) sb.setCharAt(i, '\0');
        sb.setLength(0);
    }

    private static byte[] charsToBytes(char[] chars) {
        java.nio.ByteBuffer bb = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars));
        byte[] out = new byte[bb.remaining()];
        bb.get(out);
        if (bb.hasArray()) Arrays.fill(bb.array(), (byte) 0);
        return out;
    }

    private static char[] bytesToChars(byte[] bytes) {
        java.nio.CharBuffer cb = StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(bytes));
        char[] out = new char[cb.remaining()];
        cb.get(out);
        if (cb.hasArray()) Arrays.fill(cb.array(), '\0');
        return out;
    }

    /**
     * Parses the serialized vault directly from a char[] — never wraps the whole
     * plaintext (every saved password) in an immutable String. Only label/username
     * (not secret) go through short-lived Strings; the password field is unescaped
     * straight into a char[].
     */
    private static List<CredentialEntry> deserialize(char[] chars) {
        Map<String, CredentialEntry> map = new LinkedHashMap<>();
        int i = 0, n = chars.length;
        while (i < n) {
            int lineStart = i;
            while (i < n && chars[i] != '\n') i++;
            int lineEnd = i;
            if (i < n) i++; // skip '\n'

            int s = lineStart, e = lineEnd;
            while (s < e && Character.isWhitespace(chars[s])) s++;
            while (e > s && Character.isWhitespace(chars[e - 1])) e--;
            if (s >= e) continue;

            int eq = -1;
            for (int k = s; k < e; k++) { if (chars[k] == '=') { eq = k; break; } }
            if (eq < 0) continue;

            String key = new String(chars, s, eq - s);
            String[] p = key.split("\\.", 3);          // ["e", id, field]
            if (p.length != 3 || !"e".equals(p[0])) continue;
            CredentialEntry ce = map.computeIfAbsent(p[1], id -> {
                CredentialEntry x = new CredentialEntry(); x.id = id; return x;
            });

            int valStart = eq + 1, valEnd = e;
            switch (p[2]) {
                case "l" -> ce.label    = unesc(new String(chars, valStart, valEnd - valStart));
                case "u" -> ce.username = unesc(new String(chars, valStart, valEnd - valStart));
                case "k" -> ce.keyPath  = unesc(new String(chars, valStart, valEnd - valStart));
                case "p" -> ce.password = unescChars(chars, valStart, valEnd);
            }
        }
        return new ArrayList<>(map.values());
    }

    /** Single-pass unescape straight into a char[] — mirrors escChars()'s encoding exactly. */
    private static char[] unescChars(char[] src, int start, int end) {
        char[] out = new char[end - start];
        int o = 0;
        for (int i = start; i < end; i++) {
            char c = src[i];
            if (c == '\\' && i + 1 < end) {
                char next = src[i + 1];
                if (next == '\\' || next == 'n' || next == '=') {
                    out[o++] = (next == 'n') ? '\n' : next;
                    i++;
                    continue;
                }
            }
            out[o++] = c;
        }
        return o == out.length ? out : Arrays.copyOf(out, o);
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
     * Unescape a vault-serialized value in a single left-to-right pass — mirrors esc()
     * exactly (same logic as unescChars()). Chained String.replace() is NOT a correct
     * inverse: an earlier de-escape can produce a sequence the next replace re-matches
     * (e.g. a literal backslash+'n' would wrongly become backslash+newline).
     */
    private static String unesc(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (next == '\\' || next == 'n' || next == '=') {
                    out.append(next == 'n' ? '\n' : next);
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    // -----------------------------------------------------------------------
    // Crypto helpers
    // -----------------------------------------------------------------------

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static SecretKey deriveKey(char[] password, byte[] salt, int iterations) throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
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
