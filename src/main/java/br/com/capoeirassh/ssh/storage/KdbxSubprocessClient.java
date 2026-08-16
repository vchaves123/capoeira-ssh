package br.com.capoeirassh.ssh.storage;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Talks to the disposable {@code KdbxReaderMain} subprocess over stdin/stdout pipes — the master
 * password and the fetched entry password never touch argv, an environment variable, or a file,
 * only private pipes between this process and the child.
 *
 * See {@code br.com.capoeirassh.kdbxreader.KdbxReaderMain} for why this parsing happens in a
 * separate OS process at all rather than in-process: the KeePassJava2 library holds decrypted
 * KeePass data as ordinary {@code String}s, which this JVM has no supported way to force-zero.
 * Terminating the whole child process is the only real guarantee that content doesn't linger —
 * OS process teardown reclaims all of a process's memory deterministically, unlike this JVM's
 * garbage collector, which is never forced to run and doesn't scrub memory even when it does.
 */
public final class KdbxSubprocessClient {

    private KdbxSubprocessClient() {}

    private static final long SUBPROCESS_TIMEOUT_SECONDS = 30;

    public record KdbxEntryInfo(String uuid, String groupPath, String title, String username, String url) {
        /** Label shown in the import picker — mirrors CredentialEntry.toString()'s fallback. */
        public String display() { return title.isBlank() ? username : title; }
    }

    public static final class KdbxException extends Exception {
        private static final long serialVersionUID = 1L;
        public enum Reason { WRONG_PASSWORD, ENTRY_NOT_FOUND, FILE_NOT_FOUND, OTHER }
        public final Reason reason;
        KdbxException(Reason reason, String message) { super(message); this.reason = reason; }
    }

    /** Reports how many entries have been read so far, as they stream in — lets a caller show
     *  real progress ("N found") instead of a static "please wait" while a large database is
     *  still being walked. */
    public interface EntryProgressListener { void onEntryFound(int countSoFar); }

    /** Lists every entry in the .kdbx file (title/username/url/group path — never passwords).
     *  {@code masterPassword} is read but not modified or retained by this method; the caller
     *  still owns it and is responsible for zeroing it. */
    public static List<KdbxEntryInfo> listEntries(Path kdbxFile, char[] masterPassword) throws KdbxException {
        return listEntries(kdbxFile, masterPassword, count -> {});
    }

    /** Same as {@link #listEntries(Path, char[])}, but invokes {@code listener} after each entry
     *  is parsed off the child's stdout, rather than only once the whole list is available — the
     *  reader process prints one line per entry as it walks the file, so this reports genuine
     *  progress rather than a fixed "please wait". {@code listener} may be called from a
     *  background thread; it's the caller's job to hop back to the UI thread if needed. */
    public static List<KdbxEntryInfo> listEntries(Path kdbxFile, char[] masterPassword,
                                                   EntryProgressListener listener) throws KdbxException {
        List<String> cmd = buildCommand(KdbxReaderModes.LIST, kdbxFile, null);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process proc = null;
        byte[] masterPasswordBytes = null;
        List<KdbxEntryInfo> out = new ArrayList<>();
        try {
            proc = pb.start();
            final Process startedProc = proc;
            masterPasswordBytes = charsToBytes(masterPassword);

            byte[][] stderrHolder = new byte[1][];
            Thread errThread = Thread.ofVirtual().name("kdbx-reader-stderr").start(() -> {
                try { stderrHolder[0] = readAll(startedProc.getErrorStream()); } catch (IOException ignored) {}
            });

            try (OutputStream stdin = proc.getOutputStream()) {
                stdin.write(masterPasswordBytes);
                stdin.flush();
            } // closing stdin signals EOF — the child reads it as "master password complete"

            // Line-buffered, not a bulk read: KdbxReaderMain flushes after every entry (a print()
            // call whose string contains '\n' auto-flushes System.out), so each readLine() here
            // returns as soon as the child has found one more entry, not only once it's done.
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isBlank()) continue;
                    String[] p = line.split("\t", -1);
                    if (p.length < 5) continue;
                    out.add(new KdbxEntryInfo(p[0], p[1], p[2], p[3], p[4]));
                    listener.onEntryFound(out.size());
                }
            }

            errThread.join(TimeUnit.SECONDS.toMillis(SUBPROCESS_TIMEOUT_SECONDS));
            boolean finished = proc.waitFor(SUBPROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) throw new KdbxException(KdbxException.Reason.OTHER, "kdbx reader subprocess timed out");

            int exitCode = proc.exitValue();
            if (exitCode != 0) {
                String stderrText = stderrHolder[0] != null
                    ? new String(stderrHolder[0], StandardCharsets.UTF_8) : "";
                throw toException(new ProcResult(exitCode, new byte[0], stderrText), kdbxFile);
            }
            return out;
        } catch (IOException | InterruptedException ex) {
            throw new KdbxException(KdbxException.Reason.OTHER, "Could not run kdbx reader: " + ex.getMessage());
        } finally {
            if (masterPasswordBytes != null) Arrays.fill(masterPasswordBytes, (byte) 0);
            if (proc != null) {
                proc.destroyForcibly();
                try { proc.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            }
        }
    }

    /** Fetches the live password of one entry, identified by its KeePass UUID. Returns a fresh
     *  {@code char[]} the caller owns and must zero after use — never materialized as a String
     *  on this side of the process boundary. {@code masterPassword} is read but not modified or
     *  retained; the caller still owns it. */
    public static char[] fetchPassword(Path kdbxFile, char[] masterPassword, String entryUuid) throws KdbxException {
        ProcResult r = runReader(KdbxReaderModes.PASSWORD, kdbxFile, entryUuid, masterPassword);
        if (r.exitCode != 0) throw toException(r, kdbxFile);
        char[] chars = bytesToChars(r.stdoutBytes);
        Arrays.fill(r.stdoutBytes, (byte) 0);
        return chars;
    }

    // -----------------------------------------------------------------------

    private record ProcResult(int exitCode, byte[] stdoutBytes, String stderrText) {}

    private static List<String> buildCommand(String mode, Path kdbxFile, String entryUuid) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExecutable());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("br.com.capoeirassh.kdbxreader.KdbxReaderMain");
        cmd.add(mode);
        cmd.add(kdbxFile.toString());
        if (entryUuid != null) cmd.add(entryUuid);
        return cmd;
    }

    private static ProcResult runReader(String mode, Path kdbxFile, String entryUuid, char[] masterPassword)
            throws KdbxException {
        ProcessBuilder pb = new ProcessBuilder(buildCommand(mode, kdbxFile, entryUuid));
        Process proc = null;
        byte[] masterPasswordBytes = null;
        try {
            proc = pb.start();
            final Process startedProc = proc;
            masterPasswordBytes = charsToBytes(masterPassword);

            // Drain stderr on a separate thread so a chatty error message can't deadlock this
            // thread against a full OS pipe buffer while it's still busy writing stdin/reading
            // stdout. Output here is at most one short line, but this is cheap insurance.
            byte[][] stderrHolder = new byte[1][];
            Thread errThread = Thread.ofVirtual().name("kdbx-reader-stderr").start(() -> {
                try { stderrHolder[0] = readAll(startedProc.getErrorStream()); } catch (IOException ignored) {}
            });

            try (OutputStream stdin = proc.getOutputStream()) {
                stdin.write(masterPasswordBytes);
                stdin.flush();
            } // closing stdin signals EOF — the child reads it as "master password complete"

            byte[] stdoutBytes = readAll(proc.getInputStream());
            errThread.join(TimeUnit.SECONDS.toMillis(SUBPROCESS_TIMEOUT_SECONDS));

            boolean finished = proc.waitFor(SUBPROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) throw new KdbxException(KdbxException.Reason.OTHER, "kdbx reader subprocess timed out");

            String stderrText = stderrHolder[0] != null
                ? new String(stderrHolder[0], StandardCharsets.UTF_8) : "";
            return new ProcResult(proc.exitValue(), stdoutBytes, stderrText);
        } catch (IOException | InterruptedException ex) {
            throw new KdbxException(KdbxException.Reason.OTHER, "Could not run kdbx reader: " + ex.getMessage());
        } finally {
            if (masterPasswordBytes != null) Arrays.fill(masterPasswordBytes, (byte) 0);
            if (proc != null) {
                proc.destroyForcibly();
                try { proc.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            }
        }
    }

    private static KdbxException toException(ProcResult r, Path kdbxFile) {
        return switch (r.exitCode) {
            case 2 -> new KdbxException(KdbxException.Reason.WRONG_PASSWORD,
                "Wrong master password, or \"" + kdbxFile.getFileName() + "\" is not a readable KeePass file.");
            case 3 -> new KdbxException(KdbxException.Reason.ENTRY_NOT_FOUND,
                "That entry no longer exists in \"" + kdbxFile.getFileName() + "\" (renamed, moved, or deleted).");
            case 4 -> new KdbxException(KdbxException.Reason.FILE_NOT_FOUND,
                "KeePass file not found: " + kdbxFile);
            default -> new KdbxException(KdbxException.Reason.OTHER,
                "kdbx reader failed (exit " + r.exitCode + "): " + r.stderrText);
        };
    }

    private static String javaExecutable() {
        String javaHome = System.getProperty("java.home");
        String exe = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
            ? "java.exe" : "java";
        return Path.of(javaHome, "bin", exe).toString();
    }

    /** Reads the entirety of {@code in} into a byte[], scrubbing every intermediate buffer this
     *  allocates along the way (not just the final result) once superseded by a bigger one —
     *  unlike {@link ByteArrayOutputStream}, which grows by abandoning its old backing array as
     *  ordinary unzeroed garbage and has no supported way to scrub it. {@code in} carries the
     *  fetched entry password on the stdout path this feeds — the same "can't force-zero it"
     *  problem the disposable-subprocess design exists to avoid at the process level, one level
     *  down, at the buffer used to shuttle those bytes across the pipe. The caller owns the
     *  returned array and must zero it after use, same as every other byte[]-returning method
     *  in this class. */
    private static byte[] readAll(InputStream in) throws IOException {
        byte[] buf = new byte[4096];
        int len = 0, n;
        while ((n = in.read(buf, len, buf.length - len)) != -1) {
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

    private static byte[] charsToBytes(char[] chars) {
        ByteBuffer bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
        byte[] out = new byte[bb.remaining()];
        bb.get(out);
        if (bb.hasArray()) Arrays.fill(bb.array(), (byte) 0);
        return out;
    }

    private static char[] bytesToChars(byte[] bytes) {
        CharBuffer cb = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes));
        char[] out = new char[cb.remaining()];
        cb.get(out);
        if (cb.hasArray()) Arrays.fill(cb.array(), '\0');
        return out;
    }

    /** Mode strings shared with KdbxReaderMain's wire protocol — duplicated (not a shared
     *  constant class) because the reader and this client intentionally never share a package:
     *  the reader's code only ever runs inside the disposable child process. */
    private static final class KdbxReaderModes {
        static final String LIST     = "list";
        static final String PASSWORD = "password";
    }
}
