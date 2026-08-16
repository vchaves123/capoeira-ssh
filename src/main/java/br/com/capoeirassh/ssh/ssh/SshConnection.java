package br.com.capoeirassh.ssh.ssh;

import br.com.capoeirassh.ssh.model.SessionInfo;
import com.jcraft.jsch.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public class SshConnection implements TerminalConnection {

    private JSch         jsch;
    private Session      session;
    private ChannelShell channel;
    private InputStream  input;
    private OutputStream output;
    /** Live toggle read by the Logger registered in connect() — mutable via {@link #setVerbose}
     *  so turning verbose off/on while already connected takes effect immediately (JSch keeps
     *  logging channel/keepalive/rekey activity for the life of the session, not just the
     *  initial handshake). Volatile: flipped from the UI thread, read from whatever thread
     *  JSch's logger fires on. */
    private volatile boolean verboseEnabled;

    /**
     * @param info        session configuration
     * @param password    plaintext password or passphrase as char[] (zeroed after use); null = no password
     * @param display     SWT display — used to show the host-key verification dialog on the UI thread
     * @param verboseSink when {@code info.sshVerbose} is true, receives one formatted line per
     *                    SSH protocol log message (key exchange, host key, auth negotiation) —
     *                    the JSch equivalent of {@code ssh -vvv}; ignored when {@code null} or
     *                    when {@code info.sshVerbose} is false
     */
    @Override
    public void connect(SessionInfo info, char[] password, Display display,
                         java.util.function.Consumer<String> verboseSink) throws Exception {
        try {
            jsch = new JSch();
            applyKnownHosts(jsch);

            if (info.authType == SessionInfo.AuthType.PRIVATE_KEY
                    && info.keyPath != null && !info.keyPath.isBlank()) {
                byte[] passBytes = (password != null && password.length > 0) ? toBytes(password) : null;
                try {
                    jsch.addIdentity(info.keyPath, passBytes);
                } finally {
                    if (passBytes != null) Arrays.fill(passBytes, (byte) 0);
                }
            }

            session = jsch.getSession(info.username, info.host, info.port);

            verboseEnabled = info.sshVerbose;
            if (verboseSink != null) {
                // Per-Session logger (this mwiede fork adds it beyond upstream jcraft's
                // process-wide static JSch.setLogger) — so turning verbose on for one tab
                // never leaks handshake logs into any other open session. Registered
                // unconditionally (gated by verboseEnabled inside the callbacks, not here) so
                // setVerbose() can turn logging on/off live without re-registering mid-session.
                session.setLogger(new Logger() {
                    @Override public boolean isEnabled(int level) { return verboseEnabled; }
                    @Override public void log(int level, String message) {
                        if (verboseEnabled) verboseSink.accept(levelName(level) + ": " + message);
                    }
                });
            }

            // "ask" — JSch calls UserInfo.promptYesNo for unknown hosts and saves accepted keys
            // to the known_hosts file automatically.  "yes" would reject all unknown hosts silently.
            session.setConfig("StrictHostKeyChecking", "ask");
            session.setUserInfo(new SwtHostVerifier(display, info.host, info.port));
            session.setConfig("ServerAliveInterval", "30");

            if (info.authType == SessionInfo.AuthType.PASSWORD
                    || info.authType == SessionInfo.AuthType.SAVED_CREDENTIAL) {
                byte[] passBytes = (password != null) ? toBytes(password) : new byte[0];
                try {
                    session.setPassword(passBytes);
                } finally {
                    Arrays.fill(passBytes, (byte) 0);
                }
                session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
            } else {
                session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password");
            }

            session.setTimeout(15_000);
            session.connect(15_000);

            channel = (ChannelShell) session.openChannel("shell");
            String ptyType = (info.terminalType != null && !info.terminalType.isBlank())
                ? info.terminalType : "xterm-256color";
            channel.setPtyType(ptyType);
            channel.setPtySize(80, 24, 640, 480);
            channel.setTerminalMode(buildPtyModes());

            input  = channel.getInputStream();
            output = channel.getOutputStream();

            channel.connect(15_000);
        } finally {
            // Always wipe the caller's secret, even if the connection fails partway through.
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    /** Turns SSH protocol log output on/off for the live connection immediately — no
     *  reconnect needed, since the Logger registered in connect() re-checks this on every call. */
    @Override
    public void setVerbose(boolean on) { this.verboseEnabled = on; }

    /** Synchronized: a multi-line paste is now sent line-by-line from a background thread
     *  (see TerminalTab.sendPastedLines), so this can race with the UI thread's own key-typed
     *  sends onto the same OutputStream without this guard. */
    @Override
    public synchronized void send(byte[] data) throws IOException {
        output.write(data);
        output.flush();
    }

    @Override
    public void updatePtySize(int cols, int rows, int widthPx, int heightPx) {
        if (channel != null && channel.isConnected())
            channel.setPtySize(cols, rows, widthPx, heightPx);
    }

    @Override
    public InputStream getInputStream() { return input; }

    @Override
    public boolean isConnected() {
        return channel != null && channel.isConnected();
    }

    @Override
    public void close() {
        try { if (channel != null) channel.disconnect();  } catch (Exception ignored) {}
        try { if (session != null) session.disconnect();  } catch (Exception ignored) {}
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Lazily built once per process and then reused by every connection — see
     *  {@link #applyKnownHosts} for why this must be a single shared object rather than each
     *  connection loading its own. */
    private static volatile HostKeyRepository sharedKnownHosts;

    /** Builds (on first use) or returns the ONE {@link HostKeyRepository} every connection in this
     *  process attaches to, loaded from {@code ~/.capoeira/known_hosts}. Deliberately package-
     *  private, not exposed on the shared repository directly, so callers keep going through
     *  {@link #applyKnownHosts}. */
    private static synchronized HostKeyRepository sharedHostKeyRepository() throws IOException, JSchException {
        if (sharedKnownHosts == null) {
            Path knownHosts = Path.of(System.getProperty("user.home"), ".capoeira", "known_hosts");
            if (!Files.exists(knownHosts))
                br.com.capoeirassh.ssh.storage.SecureFiles.write(knownHosts, new byte[0]);
            // A throwaway JSch instance purely to build the KnownHosts object via its own
            // setKnownHosts() — the instance itself is discarded immediately afterward; only the
            // HostKeyRepository it built is kept and reused.
            JSch bootstrap = new JSch();
            bootstrap.setKnownHosts(knownHosts.toString());
            sharedKnownHosts = bootstrap.getHostKeyRepository();
        }
        return sharedKnownHosts;
    }

    /**
     * Attaches {@code jsch} to the app's known_hosts trust store — the SAME {@link HostKeyRepository}
     * instance every other connection in this process also attaches to, not a fresh one loaded from
     * disk each time.
     *
     * <p>Why this matters: every {@link SshConnection#connect} (one per terminal tab, each on its
     * own background thread) and every {@link SftpConnection#connect} (one per upload/download)
     * used to call {@code jsch.setKnownHosts(path)} on its own brand-new {@code JSch} instance.
     * JSch's {@code KnownHosts.sync()} — invoked when a new/changed host key is accepted — is
     * {@code synchronized} only on that {@code KnownHosts} object's OWN monitor, so two
     * independent instances (each loaded from whatever was on disk at the time, with no shared
     * lock) gave zero mutual exclusion against each other: two connections accepting a host key
     * close together could each truncate-and-rewrite the whole file from an incomplete in-memory
     * view, losing or corrupting an already-accepted entry (JSch still fails closed on a real
     * mismatch — this doesn't bypass verification — but a lost entry means a spurious "unknown/
     * changed host key" re-prompt for a host the user already trusted, eroding that warning's
     * credibility for the one case it exists to catch). Routing every connection through one
     * shared {@link HostKeyRepository} closes that race by construction.
     *
     * <p>Deliberately NOT shared: identities/private keys, which stay scoped to each connection's
     * own {@code JSch} instance (added via {@code jsch.addIdentity} in {@link #connect}) rather
     * than accumulating indefinitely in a process-wide singleton — that would trade one hygiene
     * problem for another.
     *
     * <p>Package-private so {@link SftpConnection} — an independent SSH connection used for one
     * SFTP transfer, not tied to any terminal tab's session — shares the same trust store rather
     * than re-verifying host keys the terminal side already accepted. */
    static void applyKnownHosts(JSch jsch) throws IOException, JSchException {
        jsch.setHostKeyRepository(sharedHostKeyRepository());
    }

    /**
     * Builds SSH terminal modes (RFC 4254 §8) for a sane PTY initial state.
     * Each entry: opcode (1 byte) + value (4 bytes big-endian). Ends with 0x00.
     */
    private static byte[] buildPtyModes() {
        int[][] modes = {
            {  1,   3 },   // VINTR  = Ctrl+C
            {  2,  28 },   // VQUIT  = Ctrl+\
            {  3, 127 },   // VERASE = DEL
            {  4,  21 },   // VKILL  = Ctrl+U
            {  5,   4 },   // VEOF   = Ctrl+D
            {  8,  17 },   // VSTART = Ctrl+Q
            {  9,  19 },   // VSTOP  = Ctrl+S
            { 10,  26 },   // VSUSP  = Ctrl+Z
            { 12,  18 },   // VREPRINT = Ctrl+R
            { 13,  23 },   // VWERASE  = Ctrl+W
            { 14,  22 },   // VLNEXT   = Ctrl+V
            { 36,   1 },   // ICRNL  = 1
            { 38,   1 },   // IXON   = 1
            { 50,   1 },   // ISIG   = 1
            { 51,   1 },   // ICANON = 1
            { 53,   1 },   // ECHO   = 1
            { 54,   1 },   // ECHOE  = 1
            { 55,   1 },   // ECHOK  = 1
            { 59,   1 },   // IEXTEN = 1
            { 61,   1 },   // ECHOKE = 1
            { 70,   1 },   // OPOST  = 1
            { 72,   1 },   // ONLCR  = 1
            { 91,   1 },   // CS8    = 1
        };
        byte[] buf = new byte[modes.length * 5 + 1];
        int i = 0;
        for (int[] m : modes) {
            buf[i++] = (byte) m[0];
            buf[i++] = (byte) ((m[1] >> 24) & 0xFF);
            buf[i++] = (byte) ((m[1] >> 16) & 0xFF);
            buf[i++] = (byte) ((m[1] >>  8) & 0xFF);
            buf[i++] = (byte)  (m[1]        & 0xFF);
        }
        buf[i] = 0x00; // TTY_OP_END
        return buf;
    }

    /** Maps JSch's numeric log level to the short label used in the verbose sink output. */
    private static String levelName(int level) {
        return switch (level) {
            case Logger.DEBUG -> "debug";
            case Logger.INFO  -> "info";
            case Logger.WARN  -> "warn";
            case Logger.ERROR -> "error";
            case Logger.FATAL -> "fatal";
            default           -> "log";
        };
    }

    /** Convert char[] to UTF-8 bytes without creating an intermediate String. Package-private
     *  so {@link SftpConnection} can reuse it. */
    static byte[] toBytes(char[] chars) {
        ByteBuffer bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
        byte[] b = new byte[bb.remaining()];
        bb.get(b);
        if (bb.hasArray()) Arrays.fill(bb.array(), (byte) 0);   // wipe the encoder's backing array
        return b;
    }

    // -----------------------------------------------------------------------
    // Host-key verifier — runs on SSH thread, shows SWT dialog via syncExec
    // -----------------------------------------------------------------------

    /** Package-private (not private) so {@link SftpConnection} can reuse it too. */
    static final class SwtHostVerifier implements UserInfo {

        private final Display display;
        private final String  host;
        private final int     port;

        SwtHostVerifier(Display display, String host, int port) {
            this.display = display;
            this.host    = host;
            this.port    = port;
        }

        /**
         * JSch's {@code UserInfo} interface passes no structured reason — only this message
         * string — so telling a changed host key (possible MITM/DNS-spoofing) apart from an
         * ordinary first-time connection means sniffing JSch's own wording for the changed-key
         * case ("WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!",
         * {@code com.jcraft.jsch.Session#checkHost}). A benign unknown-key prompt instead reads
         * "The authenticity of host ... can't be established."
         */
        static boolean isHostKeyChangedMessage(String message) {
            return message != null
                && message.toUpperCase(java.util.Locale.ROOT).contains("IDENTIFICATION HAS CHANGED");
        }

        @Override
        public boolean promptYesNo(String message) {
            boolean keyChanged = isHostKeyChangedMessage(message);

            boolean[] result = {false};
            display.syncExec(() -> {
                Shell active = display.getActiveShell();
                MessageBox mb = new MessageBox(
                    active != null ? active : new Shell(display),
                    (keyChanged ? SWT.ICON_ERROR : SWT.ICON_WARNING) | SWT.YES | SWT.NO);
                mb.setText(keyChanged
                    ? "⚠ HOST KEY CHANGED — Possible Attack — " + host + ":" + port
                    : "Unknown Host Key — " + host + ":" + port);
                mb.setMessage(message + (keyChanged
                    ? "\n\nThis will delete the old key and save the new one to "
                      + "~/.capoeira/known_hosts. Only continue if you are certain this change "
                      + "is expected (e.g. the server was reinstalled)."
                    : "\n\nAccept this host key and continue connecting?\n"
                      + "The key will be saved to ~/.capoeira/known_hosts."));
                result[0] = mb.open() == SWT.YES;
            });
            return result[0];
        }

        // We handle passwords ourselves — these are never called.
        @Override public String  getPassphrase()              { return null;  }
        @Override public String  getPassword()                { return null;  }
        @Override public boolean promptPassphrase(String m)   { return false; }
        @Override public boolean promptPassword(String m)     { return false; }
        @Override public void    showMessage(String m)        {}
    }
}
