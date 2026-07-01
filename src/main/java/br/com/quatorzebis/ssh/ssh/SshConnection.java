package br.com.quatorzebis.ssh.ssh;

import br.com.quatorzebis.ssh.model.SessionInfo;
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

public class SshConnection {

    private JSch         jsch;
    private Session      session;
    private ChannelShell channel;
    private InputStream  input;
    private OutputStream output;

    /**
     * @param info     session configuration
     * @param password plaintext password or passphrase as char[] (zeroed after use); null = no password
     * @param display  SWT display — used to show the host-key verification dialog on the UI thread
     */
    public void connect(SessionInfo info, char[] password, Display display) throws Exception {
        jsch = new JSch();

        // Known-hosts file — host keys are stored here and verified on every connection.
        // Pre-create with owner-only permissions so JSch doesn't create it world-readable.
        Path knownHosts = Path.of(System.getProperty("user.home"), ".14bis", "known_hosts");
        if (!Files.exists(knownHosts))
            br.com.quatorzebis.ssh.storage.SecureFiles.write(knownHosts, new byte[0]);
        jsch.setKnownHosts(knownHosts.toString());

        if (info.authType == SessionInfo.AuthType.PRIVATE_KEY
                && info.keyPath != null && !info.keyPath.isBlank()) {
            byte[] passBytes = (password != null && password.length > 0) ? toBytes(password) : null;
            jsch.addIdentity(info.keyPath, passBytes);
            if (passBytes != null) Arrays.fill(passBytes, (byte) 0);
        }

        session = jsch.getSession(info.username, info.host, info.port);

        // "ask" — JSch calls UserInfo.promptYesNo for unknown hosts and saves accepted keys
        // to the known_hosts file automatically.  "yes" would reject all unknown hosts silently.
        session.setConfig("StrictHostKeyChecking", "ask");
        session.setUserInfo(new SwtHostVerifier(display, info.host, info.port));
        session.setConfig("ServerAliveInterval", "30");

        if (info.authType == SessionInfo.AuthType.PASSWORD
                || info.authType == SessionInfo.AuthType.SAVED_CREDENTIAL) {
            byte[] passBytes = (password != null) ? toBytes(password) : new byte[0];
            session.setPassword(passBytes);
            Arrays.fill(passBytes, (byte) 0);
            session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        } else {
            session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password");
        }

        // Zero the caller's array — we have already extracted what we need above.
        if (password != null) Arrays.fill(password, '\0');

        session.setTimeout(15_000);
        session.connect(15_000);

        channel = (ChannelShell) session.openChannel("shell");
        channel.setPtyType("xterm-256color");
        channel.setPtySize(80, 24, 640, 480);

        input  = channel.getInputStream();
        output = channel.getOutputStream();

        channel.connect(15_000);
    }

    public void send(byte[] data) throws IOException {
        output.write(data);
        output.flush();
    }

    public void updatePtySize(int cols, int rows, int widthPx, int heightPx) {
        if (channel != null && channel.isConnected())
            channel.setPtySize(cols, rows, widthPx, heightPx);
    }

    public InputStream getInputStream() { return input; }

    public boolean isConnected() {
        return channel != null && channel.isConnected();
    }

    public void close() {
        try { if (channel != null) channel.disconnect();  } catch (Exception ignored) {}
        try { if (session != null) session.disconnect();  } catch (Exception ignored) {}
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Convert char[] to UTF-8 bytes without creating an intermediate String. */
    private static byte[] toBytes(char[] chars) {
        ByteBuffer bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
        byte[] b = new byte[bb.remaining()];
        bb.get(b);
        return b;
    }

    // -----------------------------------------------------------------------
    // Host-key verifier — runs on SSH thread, shows SWT dialog via syncExec
    // -----------------------------------------------------------------------

    private static final class SwtHostVerifier implements UserInfo {

        private final Display display;
        private final String  host;
        private final int     port;

        SwtHostVerifier(Display display, String host, int port) {
            this.display = display;
            this.host    = host;
            this.port    = port;
        }

        @Override
        public boolean promptYesNo(String message) {
            boolean[] result = {false};
            display.syncExec(() -> {
                Shell active = display.getActiveShell();
                MessageBox mb = new MessageBox(
                    active != null ? active : new Shell(display),
                    SWT.ICON_WARNING | SWT.YES | SWT.NO);
                mb.setText("Unknown Host Key — " + host + ":" + port);
                mb.setMessage(message
                    + "\n\nAccept this host key and continue connecting?\n"
                    + "The key will be saved to ~/.14bis/known_hosts.");
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
