package br.com.capoeirassh.ssh.storage;

import br.com.capoeirassh.ssh.model.CredentialEntry;
import br.com.capoeirassh.ssh.model.SessionInfo;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.util.*;
import java.util.zip.*;

/**
 * Creates and reads encrypted Capoeira SSH backup bundles (.capoeira-backup).
 *
 * Binary format:
 *   [4]  magic  "CAPB"
 *   [1]  version = 1
 *   [16] PBKDF2-SHA256 salt
 *   [12] AES-GCM IV
 *   [N]  AES-256-GCM ciphertext  (plaintext = a ZIP archive, see below)
 *
 * ZIP contents:
 *   sessions/                 – *.session files, group subdirs preserved
 *   credentials.dat           – optional; serialized credential entries (plaintext inside
 *                               the already-encrypted bundle; requires unlocked vault to export)
 */
public final class BackupBundle {

    private static final byte[] MAGIC        = { 'C', 'A', 'P', 'B' };
    private static final byte   VERSION      = 2;         // v2 header self-describes KDF params
    private static final int    KDF_PBKDF2   = 1;         // KDF-algo id in the v2 header
    private static final int    LEGACY_ITER  = 120_000;   // v1 bundles: iteration count implicit
    private static final int    CURRENT_ITER = 600_000;   // OWASP-2023 baseline for new bundles

    private BackupBundle() {}

    // ── Export ────────────────────────────────────────────────────────────────

    public static void export(Path destination, char[] password, boolean includeVault)
            throws Exception {
        byte[] zip = buildZip(includeVault);
        byte[] enc = encrypt(zip, password);
        SecureFiles.write(destination, enc);
    }

    private static byte[] buildZip(boolean includeVault) throws IOException {
        Path base = sessionsRoot();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            if (Files.exists(base)) {
                try (var walk = Files.walk(base)) {
                    for (Path p : walk.filter(f -> f.toString().endsWith(".session")).toList()) {
                        // Never follow a symlink into the export — a link planted under
                        // sessions/ (by anyone with write access there) could otherwise smuggle
                        // an arbitrary file's real content into the bundle under an innocuous
                        // session-looking entry name.
                        if (Files.isSymbolicLink(p)) continue;
                        String rel = base.relativize(p).toString().replace('\\', '/');
                        zip.putNextEntry(new ZipEntry("sessions/" + rel));
                        zip.write(Files.readAllBytes(p));
                        zip.closeEntry();
                    }
                }
            }
            if (includeVault) {
                CredentialStore cs = CredentialStore.getInstance();
                if (cs.isUnlocked()) {
                    char[] credChars = CredentialStore.exportEntries(cs.getAll());
                    try {
                        byte[] credBytes = utf8Bytes(credChars);
                        zip.putNextEntry(new ZipEntry("credentials.dat"));
                        zip.write(credBytes);
                        zip.closeEntry();
                        Arrays.fill(credBytes, (byte) 0);
                    } finally {
                        Arrays.fill(credChars, '\0');
                    }
                }
            }
        }
        return baos.toByteArray();
    }

    // ── Import ────────────────────────────────────────────────────────────────

    public record ImportResult(List<SessionInfo> sessions, List<CredentialEntry> credentials) {}

    public static ImportResult importBundle(Path source, char[] password) throws Exception {
        byte[] raw = Files.readAllBytes(source);
        byte[] zip = decrypt(raw, password);
        return unzip(zip);
    }

    // Decompression-bomb guards for unzip(): a crafted bundle could otherwise contain a tiny
    // compressed entry that expands to gigabytes, or an enormous number of entries, exhausting
    // memory/disk during import. These caps are far above what any real backup ever needs
    // (session files and credentials.dat are at most a few KB each).
    private static final int  MAX_ENTRIES     = 100_000;
    private static final long MAX_ENTRY_BYTES = 10L * 1024 * 1024;   // 10 MB per entry
    private static final long MAX_TOTAL_BYTES = 200L * 1024 * 1024;  // 200 MB decompressed total

    private static ImportResult unzip(byte[] zipBytes) throws IOException {
        List<SessionInfo>     sessions    = new ArrayList<>();
        List<CredentialEntry> credentials = new ArrayList<>();
        Path                  base        = sessionsRoot();

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            int  entryCount = 0;
            long totalBytes = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > MAX_ENTRIES)
                    throw new IOException("Backup contains too many entries — refusing to import.");
                String name = entry.getName();
                byte[] data = readEntryBounded(zip, MAX_TOTAL_BYTES - totalBytes);
                totalBytes += data.length;

                if (name.equals("credentials.dat")) {
                    char[] chars = utf8Chars(data);
                    Arrays.fill(data, (byte) 0);
                    try {
                        credentials.addAll(CredentialStore.parseEntries(chars));
                    } finally {
                        Arrays.fill(chars, '\0');
                    }

                } else if (name.startsWith("sessions/") && name.endsWith(".session")) {
                    String rel  = name.substring("sessions/".length());
                    Path   dest = base.resolve(rel.replace('/', File.separatorChar));
                    Properties p = new Properties();
                    p.load(new ByteArrayInputStream(data));
                    SessionInfo s = fromProps(p, rel);
                    // Conflict: file already exists → new UUID + name suffix
                    if (Files.exists(dest)) {
                        s.id   = UUID.randomUUID().toString();
                        s.name = s.name.isBlank() ? "(imported)" : s.name + " (imported)";
                    }
                    sessions.add(s);
                }
                zip.closeEntry();
            }
        }
        return new ImportResult(sessions, credentials);
    }

    /** Reads one ZIP entry's fully-decompressed content, aborting if it (or the running total
     *  across the whole archive) exceeds the size caps — regardless of what the entry's own
     *  metadata claims, since that's attacker-controlled and not to be trusted. */
    private static byte[] readEntryBounded(ZipInputStream zip, long remainingTotalBudget) throws IOException {
        if (remainingTotalBudget <= 0)
            throw new IOException("Backup exceeds the allowed total size limit — refusing to import.");
        long cap = Math.min(MAX_ENTRY_BYTES, remainingTotalBudget);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = zip.read(buf)) != -1) {
            total += n;
            if (total > cap)
                throw new IOException("Backup entry exceeds the allowed size limit — refusing to import (possible decompression bomb).");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    // ── Crypto (AES-256-GCM + PBKDF2-SHA256) ─────────────────────────────────

    private static byte[] encrypt(byte[] pt, char[] pw) throws Exception {
        byte[] salt = new byte[16], iv = new byte[12];
        new SecureRandom().nextBytes(salt);
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, deriveKey(pw, salt, CURRENT_ITER), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(pt);
        ByteArrayOutputStream out = new ByteArrayOutputStream(MAGIC.length + 1 + 1 + 4 + 16 + 12 + ct.length);
        out.write(MAGIC);
        out.write(VERSION);                               // 2
        out.write(KDF_PBKDF2);                            // KDF-algo id
        out.write((CURRENT_ITER >>> 24) & 0xFF); out.write((CURRENT_ITER >>> 16) & 0xFF);
        out.write((CURRENT_ITER >>>  8) & 0xFF); out.write( CURRENT_ITER        & 0xFF);
        out.write(salt); out.write(iv); out.write(ct);
        return out.toByteArray();
    }

    private static byte[] decrypt(byte[] bundle, char[] pw) throws Exception {
        if (bundle.length < MAGIC.length + 1 + 16 + 12 + 16)
            throw new IOException("Invalid or corrupt backup file.");
        for (int i = 0; i < MAGIC.length; i++)
            if (bundle[i] != MAGIC[i]) throw new IOException("Not a Capoeira SSH backup file.");
        int off = MAGIC.length;
        int ver = bundle[off++] & 0xFF;
        int iter;
        if (ver == 1) {                       // legacy: KDF params were implicit
            iter = LEGACY_ITER;
        } else if (ver == 2) {                // self-describing header
            if (bundle.length < MAGIC.length + 1 + 1 + 4 + 16 + 12 + 16)
                throw new IOException("Invalid or corrupt backup file.");
            int kdfId = bundle[off++] & 0xFF;
            iter = ((bundle[off] & 0xFF) << 24) | ((bundle[off + 1] & 0xFF) << 16)
                 | ((bundle[off + 2] & 0xFF) << 8) | (bundle[off + 3] & 0xFF);
            off += 4;
            if (kdfId != KDF_PBKDF2) throw new IOException("Unsupported backup KDF id: " + kdfId);
            // Reject an absurd iteration count from an untrusted header — otherwise a crafted
            // bundle can peg a CPU core for minutes deriving a key before the (attacker-
            // controlled) file is even confirmed as wrong.
            if (iter < 1_000 || iter > 2_000_000)
                throw new IOException("Invalid backup KDF iteration count: " + iter);
        } else {
            throw new IOException("Unsupported backup version: " + ver);
        }
        byte[] salt = Arrays.copyOfRange(bundle, off, off + 16); off += 16;
        byte[] iv   = Arrays.copyOfRange(bundle, off, off + 12); off += 12;
        byte[] ct   = Arrays.copyOfRange(bundle, off, bundle.length);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, deriveKey(pw, salt, iter), new GCMParameterSpec(128, iv));
        try {
            return c.doFinal(ct);
        } catch (AEADBadTagException e) {
            throw new IOException("Wrong password or corrupt backup file.");
        }
    }

    private static SecretKey deriveKey(char[] pw, byte[] salt, int iterations)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        var spec = new PBEKeySpec(pw, salt, iterations, 256);
        byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                                     .generateSecret(spec).getEncoded();
        spec.clearPassword();
        return new SecretKeySpec(raw, "AES");
    }

    // ── Secret-safe UTF-8 conversion (no intermediate immutable String) ───────
    // credentials.dat holds every saved password in plaintext; converting it via
    // new String(...) would leave an unzeroable copy on the heap until GC, defeating
    // the char[]/zeroing discipline the vault code maintains. Use zeroable buffers.

    private static byte[] utf8Bytes(char[] chars) {
        java.nio.ByteBuffer bb = java.nio.charset.StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars));
        byte[] out = new byte[bb.remaining()];
        bb.get(out);
        if (bb.hasArray()) Arrays.fill(bb.array(), (byte) 0);
        return out;
    }

    private static char[] utf8Chars(byte[] bytes) {
        java.nio.CharBuffer cb = java.nio.charset.StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(bytes));
        char[] out = new char[cb.remaining()];
        cb.get(out);
        if (cb.hasArray()) Arrays.fill(cb.array(), '\0');
        return out;
    }

    // ── Paths ─────────────────────────────────────────────────────────────────

    private static Path sessionsRoot() {
        return Path.of(System.getProperty("user.home"), ".capoeira", "sessions");
    }

    // ── Session deserialization (mirrors SessionStorage.load()) ───────────────

    private static SessionInfo fromProps(Properties p, String rel) {
        SessionInfo s = new SessionInfo();
        // ID is derived from filename, not the stored "id" property (same as SessionStorage).
        // Take the basename splitting on BOTH separators — a malicious bundle can use '\' to
        // smuggle traversal past a '/'-only split, and '\' is a path separator on Windows.
        String fname = rel;
        int slash = Math.max(fname.lastIndexOf('/'), fname.lastIndexOf('\\'));
        if (slash >= 0) fname = fname.substring(slash + 1);
        String id = fname.endsWith(".session")
                ? fname.substring(0, fname.length() - ".session".length()) : fname;
        // Reject anything that isn't a clean id (path separators, drive letters, '.', '..',
        // blank) so it can never escape the sessions/ dir — fall back to a fresh UUID.
        s.id = isSafeId(id) ? id : UUID.randomUUID().toString();
        s.name      = p.getProperty("name",     "");
        s.host      = p.getProperty("host",     "");
        s.port      = def(p.getProperty("port",     "22"),  22);
        s.username  = p.getProperty("username", "");
        s.keyPath        = p.getProperty("keyPath",        "");
        s.group          = p.getProperty("group",          "");
        s.iconType       = p.getProperty("iconType",       "");
        s.credentialId   = p.getProperty("credentialId",   "");
        s.appearFontSize = def(p.getProperty("appearFontSize", "0"), 0);
        s.appearFontName = p.getProperty("appearFontName", "");
        int[] fg = rgb(p.getProperty("appearFg", "204,204,204"));
        s.appearFgR = fg[0]; s.appearFgG = fg[1]; s.appearFgB = fg[2];
        int[] bg = rgb(p.getProperty("appearBg", "0,0,0"));
        s.appearBgR = bg[0]; s.appearBgG = bg[1]; s.appearBgB = bg[2];
        s.logEnabled  = Boolean.parseBoolean(p.getProperty("logEnabled",  "false"));
        s.logDir      = p.getProperty("logDir",      "");
        s.logFileName = p.getProperty("logFileName", "");
        s.terminalType  = p.getProperty("terminalType",  "xterm-256color");
        s.backspaceCode = def(p.getProperty("backspaceCode", "127"), 127);
        s.sshVerbose    = Boolean.parseBoolean(p.getProperty("sshVerbose", "false"));
        s.sortOrder     = def(p.getProperty("sortOrder", "0"), 0);
        s.tags          = parseTags(p.getProperty("tags", ""));
        try { s.authType = SessionInfo.AuthType.valueOf(p.getProperty("authType", "PASSWORD")); }
        catch (Exception e) { s.authType = SessionInfo.AuthType.PASSWORD; }
        return s;
    }

    /** True only for a clean single-segment id — no path separators, drive letters, '.' or '..'. */
    private static boolean isSafeId(String id) {
        return id != null && !id.isBlank()
            && !id.equals(".") && !id.equals("..")
            && id.matches("[\\w.-]+");   // no '/', '\', ':' etc.
    }

    private static int def(String s, int d) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return d; }
    }
    private static List<String> parseTags(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String t : raw.split(",")) {
            String trimmed = t.trim();
            if (!trimmed.isEmpty() && !out.contains(trimmed)) out.add(trimmed);
            if (out.size() == 6) break;
        }
        return out;
    }
    private static int[] rgb(String s) {
        try {
            String[] p = s.split(",");
            return new int[]{ Integer.parseInt(p[0].trim()),
                              Integer.parseInt(p[1].trim()),
                              Integer.parseInt(p[2].trim()) };
        } catch (Exception e) { return new int[]{ 204, 204, 204 }; }
    }
}
