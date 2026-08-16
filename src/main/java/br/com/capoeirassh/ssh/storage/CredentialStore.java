package br.com.capoeirassh.ssh.storage;

import br.com.capoeirassh.ssh.model.CredentialEntry;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.security.SecureRandom;
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
    // v1: legacy fixed-iteration format, no self-describing header, no AAD.
    // v2: self-describing header (KDF id + iteration count), but NOT bound as AAD — this was
    //     CURRENT_VERSION prior to build 249 and is what every vault file written before that
    //     fix looks like on disk. Still readable (without AAD) for backward compatibility.
    // v3: same self-describing header, ALSO bound to the ciphertext as GCM AAD (build 249).
    //     This is the only format persist() writes going forward.
    private static final int    VERSION_NO_AAD = 2;
    private static final int    VERSION = 3;
    private static final int    SALT_LEN = 16;
    private static final int    IV_LEN   = 12;
    private static final int    KDF_PBKDF2   = 1;        // KDF-algo id stored in the v2 header
    private static final int    LEGACY_ITER  = 120_000;  // v1 files: iteration count was implicit
    private static final int    CURRENT_ITER = 600_000;  // OWASP-2023 baseline; new/migrated files

    private static final long INACTIVITY_MS = 5 * 60 * 1000L; // 5 minutes

    /** Keyed by id so addOrUpdate()/delete()/findById() are O(1) instead of a linear scan over
     *  every entry — a LinkedHashMap so getAll()/persist() still see entries in a stable,
     *  insertion-order iteration (matching what a List would have given). */
    private Map<String, CredentialEntry> entries  = new LinkedHashMap<>();
    /** Raw AES key bytes, held directly instead of wrapped in a SecretKeySpec — SecretKeySpec
     *  advertises Destroyable but destroy() throws DestroyFailedException on this JDK rather
     *  than actually zeroing anything (JDK-8160206, still unresolved), so owning the byte[]
     *  ourselves is the only way to actually wipe key material on lock()/rotation. A fresh
     *  SecretKeySpec is constructed on demand for each Cipher.init() call. */
    private byte[]                masterKeyBytes = null;
    private byte[]                salt           = null;
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
                if (masterKeyBytes != null && lastAccessMs > 0
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

    public synchronized boolean isUnlocked()   { return masterKeyBytes != null; }
    public boolean vaultExists()  { return Files.exists(VAULT); }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /** Create a brand-new vault with the given master password. Zeroes the array after use. */
    public synchronized void create(char[] masterPassword) throws Exception {
        this.salt           = randomBytes(SALT_LEN);
        this.iterations     = CURRENT_ITER;
        this.masterKeyBytes = deriveKeyBytes(masterPassword, salt, CURRENT_ITER);
        Arrays.fill(masterPassword, '\0');
        this.entries    = new LinkedHashMap<>();
        touch();
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
                // Same length guard the v2 branch already has below — without it, a truncated
                // v1-labeled file falls through to the unchecked copyOfRange calls, which
                // either silently zero-pad or throw an unchecked ArrayIndexOutOfBoundsException
                // instead of this clean rejection.
                if (raw.length < 4 + 1 + SALT_LEN + IV_LEN + 16)
                    throw new Exception("Not a Capoeira vault file.");
                iter = LEGACY_ITER;
            } else if (ver == VERSION_NO_AAD || ver == VERSION) {  // self-describing header
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
            int headerEnd   = off;   // end of the self-describing header (v2/v3) — start of IV
            byte[] iv       = Arrays.copyOfRange(raw, off, off + IV_LEN);   off += IV_LEN;
            byte[] cipher   = Arrays.copyOfRange(raw, off, raw.length);

            byte[] keyBytes = deriveKeyBytes(masterPassword, fileSalt, iter);
            Cipher aes      = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(128, iv));
            // v3 files bind their whole self-describing header (magic, version, KDF id,
            // iterations, salt) as AAD — see persist(). v1 files predate AAD entirely, and v2
            // files (every vault written before this AAD binding was added) were encrypted with
            // none either — supplying it here would make every legitimate old v1/v2 file
            // unreadable with the objectively correct password (this broke real vaults once
            // already; see the regression test for the full story).
            if (ver == VERSION) aes.updateAAD(Arrays.copyOfRange(raw, 0, headerEnd));
            byte[] plain    = aes.doFinal(cipher);   // throws AEADBadTagException on wrong key or tampered header

            this.salt           = fileSalt;
            this.iterations     = iter;
            this.masterKeyBytes = keyBytes;
            touch();
            // Decode straight to char[] — never materialize the whole plaintext vault
            // (every saved password) as an immutable String, which can't be zeroed
            // and would otherwise linger on the heap until GC.
            char[] plainChars = bytesToChars(plain);
            Arrays.fill(plain, (byte) 0);
            try {
                Map<String, CredentialEntry> loaded = new LinkedHashMap<>();
                for (CredentialEntry ce : deserialize(plainChars)) loaded.put(ce.id, ce);
                this.entries = loaded;
            } finally {
                Arrays.fill(plainChars, '\0');
            }

            // Transparently upgrade legacy / weaker-KDF vaults to CURRENT_ITER, and any
            // pre-AAD (v1/v2) vault to the current AAD-bound format, with a fresh salt — the
            // password is still in hand here. Re-persisting always writes VERSION (current),
            // so this also carries a v2-at-600k-iterations vault (which the iter check alone
            // wouldn't touch) onto the AAD-bound format. Best-effort: a read-only vault dir
            // must never block a successful unlock.
            if (iter < CURRENT_ITER || ver < VERSION) {
                byte[] newKeyBytes = null;
                try {
                    byte[] newSalt = randomBytes(SALT_LEN);
                    newKeyBytes         = deriveKeyBytes(masterPassword, newSalt, CURRENT_ITER);
                    this.salt           = newSalt;
                    this.iterations     = CURRENT_ITER;
                    this.masterKeyBytes = newKeyBytes;
                    persist();
                    // Only safe to wipe the superseded key once the new one is fully committed.
                    Arrays.fill(keyBytes, (byte) 0);
                } catch (Exception rePersistFailed) {
                    // Revert to the working key/salt so in-memory state matches what's on disk —
                    // keyBytes was never touched above, so it's still intact to revert to.
                    this.salt           = fileSalt;
                    this.iterations     = iter;
                    this.masterKeyBytes = keyBytes;
                    if (newKeyBytes != null) Arrays.fill(newKeyBytes, (byte) 0);
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
        for (CredentialEntry e : entries.values()) {
            if (e.password != null) Arrays.fill(e.password, '\0');
        }
        if (masterKeyBytes != null) Arrays.fill(masterKeyBytes, (byte) 0);
        masterKeyBytes = null;
        entries        = new LinkedHashMap<>();
        salt           = null;
        // A cached external KeePass master password (see KdbxMasterPasswordCache) is just as
        // sensitive as anything in this vault — never let it outlive the vault's own lock,
        // whether that lock came from inactivity or the user's manual "Lock vault" button.
        KdbxMasterPasswordCache.getInstance().clearAll();
    }

    // -----------------------------------------------------------------------
    // CRUD
    // -----------------------------------------------------------------------

    /** Returns deep copies, not the store's own live entries — a UI dialog editing one of
     *  these in place (before the user even clicks Save) must not mutate the store's actual
     *  state, nor be able to have its password char[] zeroed out from under it by a concurrent
     *  lock()/auto-lock while the dialog is still open. */
    public synchronized List<CredentialEntry> getAll() {
        touch();
        return entries.values().stream().map(CredentialEntry::copy).toList();
    }

    public synchronized CredentialEntry findById(String id) {
        if (id == null || id.isBlank()) return null;
        touch();
        CredentialEntry e = entries.get(id);
        return e != null ? e.copy() : null;
    }

    public synchronized void addOrUpdate(CredentialEntry e) throws Exception {
        touch();
        Map<String, CredentialEntry> snapshot = new LinkedHashMap<>(entries);
        CredentialEntry previous = entries.put(e.id, e);
        try {
            persist();
            // Only safe to wipe the superseded entry's password once the new one is durably
            // persisted — every other "replace a live secret" path in this class (lock(),
            // this method's own failure-rollback below, KdbxMasterPasswordCache) zeroes the
            // outgoing char[] before dropping it; this was the one that didn't, so a stale
            // master/account password could linger unzeroed on the heap after an ordinary
            // credential edit or the KeePass "heal a corrected master password" flow.
            // previous != e guards a caller that (in principle) mutated and re-added the exact
            // same object in place — nothing in this codebase does that today, but zeroing the
            // array persist() just read from before returning would be wrong if it ever did.
            if (previous != null && previous != e && previous.password != null) {
                Arrays.fill(previous.password, '\0');
            }
        } catch (Exception ex) {
            // Roll back so a failed save never leaves e's plaintext password permanently
            // reachable from the store (and never gets silently written by some later,
            // unrelated successful save that just happens to persist() the whole list).
            entries = snapshot;
            if (e.password != null) Arrays.fill(e.password, '\0');
            throw ex;
        }
    }

    public synchronized void delete(String id) throws Exception {
        touch();
        entries.remove(id);
        persist();
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    private synchronized void persist() throws Exception {
        if (masterKeyBytes == null) throw new IllegalStateException("Vault is locked.");
        byte[] iv    = randomBytes(IV_LEN);
        Cipher aes   = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKeyBytes, "AES"), new GCMParameterSpec(128, iv));

        // Bind the whole self-describing header (magic, version, KDF id, iteration count, salt)
        // to the ciphertext as GCM additional authenticated data. Salt and iterations already
        // feed into key derivation, so tampering with them is caught indirectly today — but
        // binding the entire header by construction means that stays true even if a future
        // format change adds a header field that doesn't happen to feed into derivation, rather
        // than depending on every such field being independently re-derived from correctly.
        byte[] header = new byte[4 + 1 + 1 + 4 + SALT_LEN];
        int hOff = 0;
        System.arraycopy(MAGIC, 0, header, hOff, 4); hOff += 4;
        header[hOff++] = (byte) VERSION;
        header[hOff++] = (byte) KDF_PBKDF2;
        int it = this.iterations;
        header[hOff++] = (byte) (it >>> 24); header[hOff++] = (byte) (it >>> 16);
        header[hOff++] = (byte) (it >>> 8);  header[hOff++] = (byte) it;
        System.arraycopy(salt, 0, header, hOff, SALT_LEN);
        aes.updateAAD(header);

        // Serialize without ever forming one immutable String holding the whole
        // plaintext vault (every saved password) — extract into a char[]/byte[]
        // we can explicitly zero once the ciphertext has been produced.
        StringBuilder sb = serialize(entries.values());
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

        byte[] out = new byte[header.length + IV_LEN + ciph.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(iv,     0, out, header.length, IV_LEN);
        System.arraycopy(ciph,   0, out, header.length + IV_LEN, ciph.length);

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
        if (masterKeyBytes == null) throw new IllegalStateException("Vault is locked.");
        Set<String> usedLabels = entries.values().stream()
                .map(e -> e.label.toLowerCase())
                .collect(Collectors.toCollection(java.util.HashSet::new));

        Map<String, CredentialEntry> snapshot = new LinkedHashMap<>(entries);
        Map<String, String> remap = new java.util.LinkedHashMap<>();
        for (br.com.capoeirassh.ssh.model.CredentialEntry imp : incoming) {
            String origId = imp.id;
            imp.id = UUID.randomUUID().toString();
            remap.put(origId, imp.id);
            if (usedLabels.contains(imp.label.toLowerCase())) {
                imp.label = imp.label + " (imported)";
            }
            usedLabels.add(imp.label.toLowerCase());
            entries.put(imp.id, imp);
        }
        try {
            persist();
        } catch (Exception ex) {
            // Same rollback discipline as addOrUpdate() — a failed import must not leave
            // every incoming plaintext password permanently resident in the live store.
            entries = snapshot;
            for (br.com.capoeirassh.ssh.model.CredentialEntry imp : incoming)
                if (imp.password != null) Arrays.fill(imp.password, '\0');
            throw ex;
        }
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

    private static StringBuilder serialize(Collection<CredentialEntry> list) {
        // Pre-size to (worst-case) fit every entry without growing — StringBuilder's default
        // growth reallocates into a new backing char[] and abandons the old one (which, mid-way
        // through this loop, already contains prior entries' plaintext passwords) as ordinary
        // unzeroed garbage. Sizing up front so the buffer never needs to grow means there is
        // only ever one backing array, which wipe() below can (and does) fully zero.
        int estimate = 64;
        for (CredentialEntry e : list) {
            estimate += 32 + e.label.length() * 2 + e.username.length() * 2
                + (e.keyPath != null ? e.keyPath.length() * 2 : 0) + e.password.length * 2
                + (e.kdbxFilePath  != null ? e.kdbxFilePath.length()  * 2 : 0)
                + (e.kdbxEntryUuid != null ? e.kdbxEntryUuid.length() * 2 : 0);
        }
        StringBuilder sb = new StringBuilder(estimate);
        for (CredentialEntry e : list) {
            sb.append("e.").append(e.id).append(".l=").append(esc(e.label))   .append('\n');
            sb.append("e.").append(e.id).append(".u=").append(esc(e.username)).append('\n');
            sb.append("e.").append(e.id).append(".k=").append(esc(e.keyPath != null ? e.keyPath : "")).append('\n');
            sb.append("e.").append(e.id).append(".p=");
            escChars(e.password, sb);
            sb.append('\n');
            // kdbx-reference fields (both blank for an ordinary password/private-key entry) —
            // not secret (a file path and a KeePass-internal UUID, not credential material), so
            // plain String escaping is fine here, same as label/username/keyPath above.
            sb.append("e.").append(e.id).append(".kf=").append(esc(e.kdbxFilePath  != null ? e.kdbxFilePath  : "")).append('\n');
            sb.append("e.").append(e.id).append(".ke=").append(esc(e.kdbxEntryUuid != null ? e.kdbxEntryUuid : "")).append('\n');
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
                case "l"  -> ce.label         = unesc(new String(chars, valStart, valEnd - valStart));
                case "u"  -> ce.username       = unesc(new String(chars, valStart, valEnd - valStart));
                case "k"  -> ce.keyPath        = unesc(new String(chars, valStart, valEnd - valStart));
                case "p"  -> ce.password       = unescChars(chars, valStart, valEnd);
                // Absent entirely in vaults written before this feature — the map's default
                // CredentialEntry already leaves these as "", so an old entry deserializes as
                // an ordinary (non-kdbx-reference) credential exactly as before.
                case "kf" -> ce.kdbxFilePath   = unesc(new String(chars, valStart, valEnd - valStart));
                case "ke" -> ce.kdbxEntryUuid  = unesc(new String(chars, valStart, valEnd - valStart));
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

    /** Returns the raw derived key bytes directly, rather than wrapping them in a SecretKey —
     *  see the masterKeyBytes field comment for why. */
    private static byte[] deriveKeyBytes(char[] password, byte[] salt, int iterations) throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            return f.generateSecret(spec).getEncoded();
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
