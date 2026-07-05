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

    private static final byte[] MAGIC       = { 'C', 'A', 'P', 'B' };
    private static final byte   VERSION     = 1;
    private static final int    PBKDF2_ITER = 120_000;

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
                        byte[] credBytes = new String(credChars).getBytes(java.nio.charset.StandardCharsets.UTF_8);
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

    private static ImportResult unzip(byte[] zipBytes) throws IOException {
        List<SessionInfo>     sessions    = new ArrayList<>();
        List<CredentialEntry> credentials = new ArrayList<>();
        Path                  base        = sessionsRoot();

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                byte[] data = zip.readAllBytes();

                if (name.equals("credentials.dat")) {
                    char[] chars = new String(data, java.nio.charset.StandardCharsets.UTF_8).toCharArray();
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

    // ── Crypto (AES-256-GCM + PBKDF2-SHA256) ─────────────────────────────────

    private static byte[] encrypt(byte[] pt, char[] pw) throws Exception {
        byte[] salt = new byte[16], iv = new byte[12];
        new SecureRandom().nextBytes(salt);
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, deriveKey(pw, salt), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(pt);
        ByteArrayOutputStream out = new ByteArrayOutputStream(MAGIC.length + 1 + 16 + 12 + ct.length);
        out.write(MAGIC); out.write(VERSION); out.write(salt); out.write(iv); out.write(ct);
        return out.toByteArray();
    }

    private static byte[] decrypt(byte[] bundle, char[] pw) throws Exception {
        if (bundle.length < MAGIC.length + 1 + 16 + 12 + 16)
            throw new IOException("Invalid or corrupt backup file.");
        for (int i = 0; i < MAGIC.length; i++)
            if (bundle[i] != MAGIC[i]) throw new IOException("Not a Capoeira SSH backup file.");
        if (bundle[4] != VERSION)
            throw new IOException("Unsupported backup version: " + (bundle[4] & 0xFF));
        byte[] salt = Arrays.copyOfRange(bundle, 5,  21);
        byte[] iv   = Arrays.copyOfRange(bundle, 21, 33);
        byte[] ct   = Arrays.copyOfRange(bundle, 33, bundle.length);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, deriveKey(pw, salt), new GCMParameterSpec(128, iv));
        try {
            return c.doFinal(ct);
        } catch (AEADBadTagException e) {
            throw new IOException("Wrong password or corrupt backup file.");
        }
    }

    private static SecretKey deriveKey(char[] pw, byte[] salt)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        var spec = new PBEKeySpec(pw, salt, PBKDF2_ITER, 256);
        byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                                     .generateSecret(spec).getEncoded();
        spec.clearPassword();
        return new SecretKeySpec(raw, "AES");
    }

    // ── Paths ─────────────────────────────────────────────────────────────────

    private static Path sessionsRoot() {
        return Path.of(System.getProperty("user.home"), ".capoeira", "sessions");
    }

    // ── Session deserialization (mirrors SessionStorage.load()) ───────────────

    private static SessionInfo fromProps(Properties p, String rel) {
        SessionInfo s = new SessionInfo();
        // ID is derived from filename, not the stored "id" property (same as SessionStorage)
        String fname = rel.contains("/") ? rel.substring(rel.lastIndexOf('/') + 1) : rel;
        s.id = fname.endsWith(".session") ? fname.substring(0, fname.length() - ".session".length()) : fname;
        s.name      = p.getProperty("name",     "");
        s.host      = p.getProperty("host",     "");
        s.port      = def(p.getProperty("port",     "22"),  22);
        s.username  = p.getProperty("username", "");
        s.keyPath        = p.getProperty("keyPath",        "");
        s.group          = p.getProperty("group",          "");
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
        try { s.authType = SessionInfo.AuthType.valueOf(p.getProperty("authType", "PASSWORD")); }
        catch (Exception e) { s.authType = SessionInfo.AuthType.PASSWORD; }
        return s;
    }

    private static int def(String s, int d) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return d; }
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
