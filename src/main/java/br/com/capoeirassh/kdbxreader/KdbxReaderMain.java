package br.com.capoeirassh.kdbxreader;

import org.linguafranca.pwdb.Database;
import org.linguafranca.pwdb.Entry;
import org.linguafranca.pwdb.Group;
import org.linguafranca.pwdb.kdbx.KdbxCreds;
import org.linguafranca.pwdb.kdbx.jackson.JacksonDatabase;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

/**
 * Entry point of a disposable child process launched by {@code KdbxSubprocessClient} to read an
 * external KeePass (.kdbx) file. This class — and the KeePassJava2 library it uses — never runs
 * in the main application process.
 *
 * Why: KeePassJava2 holds decrypted KeePass data (including passwords) as ordinary {@code String}s,
 * which Java gives no supported way to forcibly zero out of memory (unlike this app's own vault,
 * which uses {@code char[]} + {@code Arrays.fill} throughout — see CredentialStore). Running the
 * parsing in its own OS process instead means that when the process exits, the operating system
 * deterministically reclaims its entire memory space — a real guarantee, unlike waiting on this
 * JVM's garbage collector, which is never forced to run and doesn't zero freed memory even when
 * it does.
 *
 * Wire protocol (all secrets travel over pipes, never argv/env):
 *   args:  [0] mode = "list" | "password"
 *          [1] path to the .kdbx file
 *          [2] entry UUID — required for "password" mode, ignored for "list"
 *   stdin: the master password, as raw UTF-8 bytes, with EOF (parent closes its write side)
 *          marking the end — never a delimiter, since a password could contain any byte.
 *   stdout ("list" mode): one line per entry, tab-separated:
 *          uuid \t groupPath \t title \t username \t url
 *          (title/username/url/groupPath are display-only and have tabs/newlines stripped;
 *          never the password)
 *   stdout ("password" mode): the raw UTF-8 bytes of the entry's password, nothing else —
 *          no trailing newline, since a password may legitimately end in one.
 *   exit codes: 0 = success
 *               2 = wrong master password / file could not be opened as a KeePass database
 *               3 = entry UUID not found in the database
 *               4 = file does not exist
 *               1 = other error (bad arguments, I/O failure, etc.)
 *   stderr: a short, sanitized reason only — never file contents or password material.
 */
public final class KdbxReaderMain {

    static final String MODE_LIST     = "list";
    static final String MODE_PASSWORD = "password";

    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = run(args);
        } catch (Throwable t) {
            System.err.println("kdbx-reader: unexpected error (" + t.getClass().getSimpleName() + ")");
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    private static int run(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: KdbxReaderMain <list|password> <kdbxFilePath> [entryUuid]");
            return 1;
        }
        String mode          = args[0];
        Path   kdbxPath      = Path.of(args[1]);
        String entryUuidArg  = args.length > 2 ? args[2] : null;

        if (!MODE_LIST.equals(mode) && !MODE_PASSWORD.equals(mode)) {
            System.err.println("kdbx-reader: unknown mode " + mode);
            return 1;
        }
        if (MODE_PASSWORD.equals(mode) && entryUuidArg == null) {
            System.err.println("kdbx-reader: missing entry uuid for password mode");
            return 1;
        }
        if (!Files.isRegularFile(kdbxPath)) {
            System.err.println("kdbx-reader: file not found");
            return 4;
        }

        byte[] masterPasswordBytes = readAllStdin();
        try {
            Database<?, ?, ?, ?> db;
            try (InputStream in = Files.newInputStream(kdbxPath)) {
                db = JacksonDatabase.load(new KdbxCreds(masterPasswordBytes), in);
            } catch (Exception ex) {
                // KeePassJava2 doesn't cleanly distinguish "wrong password" from "corrupt/
                // unsupported file" — both map here; the parent shows one generic message.
                System.err.println("kdbx-reader: cannot open database");
                return 2;
            }

            if (MODE_LIST.equals(mode)) {
                printEntries(db.getRootGroup());
                return 0;
            }

            UUID uuid;
            try {
                uuid = UUID.fromString(entryUuidArg);
            } catch (IllegalArgumentException ex) {
                System.err.println("kdbx-reader: malformed entry uuid");
                return 1;
            }
            Entry<?, ?, ?, ?> entry = db.findEntry(uuid);
            if (entry == null) {
                System.err.println("kdbx-reader: entry not found");
                return 3;
            }
            // Write the raw UTF-8 bytes straight to stdout, a pipe the parent reads directly —
            // never a file, terminal, or log — and return immediately so this process (and
            // whatever Strings the library built while parsing) tears down as soon as possible.
            OutputStream out = System.out;
            out.write(entry.getPassword().getBytes(StandardCharsets.UTF_8));
            out.flush();
            return 0;
        } finally {
            Arrays.fill(masterPasswordBytes, (byte) 0);
        }
    }

    private static void printEntries(Group<?, ?, ?, ?> group) {
        for (Entry<?, ?, ?, ?> e : group.getEntries()) {
            String uuid  = e.getUuid().toString();
            String path  = sanitize(group.getPath());
            String title = sanitize(e.getTitle());
            String user  = sanitize(e.getUsername());
            String url   = sanitize(e.getUrl());
            // A bare "\n" always — never println(), whose platform line separator is "\r\n" on
            // Windows and would leave a trailing '\r' silently stuck to the last (url) field
            // after the parent splits on "\n" alone.
            System.out.print(uuid + "\t" + path + "\t" + title + "\t" + user + "\t" + url + "\n");
        }
        for (Group<?, ?, ?, ?> g : group.getGroups()) {
            printEntries(g);
        }
    }

    /** Strips characters that would break the tab/newline-delimited wire format above. These
     *  are display-only fields (title/username/url/group path) — never the password — so lossy
     *  sanitizing is an acceptable trade-off for a dead-simple parser on the parent side. */
    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /** Reads all of stdin (the master password) into a byte[], scrubbing every intermediate
     *  buffer this allocates along the way once superseded by a bigger one — unlike
     *  {@link ByteArrayOutputStream}, which grows by abandoning its old backing array as ordinary
     *  unzeroed garbage and has no supported way to scrub it. This mirrors {@code
     *  KdbxSubprocessClient.readAll()} on the parent side of this same pipe (duplicated, not
     *  shared, for the same reason the rest of this wire protocol's constants are: this class
     *  never shares a package with the parent). The caller ({@link #run}) zeroes the returned
     *  array in its own {@code finally} block once done with it. */
    private static byte[] readAllStdin() throws IOException {
        byte[] buf = new byte[4096];
        int len = 0, n;
        while ((n = System.in.read(buf, len, buf.length - len)) != -1) {
            len += n;
            if (len == buf.length) {
                byte[] bigger = new byte[buf.length * 2];
                System.arraycopy(buf, 0, bigger, 0, len);
                Arrays.fill(buf, (byte) 0); // scrub the superseded buffer before dropping it
                buf = bigger;
            }
        }
        byte[] out = Arrays.copyOf(buf, len);
        Arrays.fill(buf, (byte) 0); // scrub the final working buffer too — `out` is the only copy kept
        return out;
    }

    private KdbxReaderMain() {}
}
