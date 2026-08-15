package br.com.capoeirassh.ssh.ssh;

import br.com.capoeirassh.ssh.model.SessionInfo;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.eclipse.swt.widgets.Display;

import java.util.Arrays;

/**
 * A standalone SSH connection dedicated to a single SFTP transfer (upload/download), completely
 * independent of any terminal tab's {@link SshConnection} — its own {@code Session}, its own
 * TCP connection to the server. Reconnecting, disconnecting, or closing the tab that started the
 * transfer has no effect on it; conversely, cancelling or finishing the transfer never touches
 * the terminal's own session.
 *
 * <p>This trades a little convenience for that isolation: unlike the terminal's session, the
 * password/passphrase can't be reused (it's zeroed right after the terminal's own connect()), so
 * the caller must resolve credentials again before calling {@link #connect} — via
 * {@code ConnectDialog}, same as a manual Reconnect. For a saved/vaulted credential or a
 * passphrase-less key this is silent; for a manually-typed password it means asking again.
 */
public class SftpConnection {

    private Session     session;
    private ChannelSftp  sftp;

    /**
     * Opens a fresh SSH session to {@code info.host}/{@code info.port} and an SFTP channel on it.
     *
     * @param password plaintext password or passphrase as char[] (zeroed after use); null = no password
     * @param display  SWT display — used to show the host-key verification dialog on the UI thread
     *                 if needed (in practice a no-op here: the key was already accepted and saved
     *                 to known_hosts by the terminal's own connection)
     */
    public ChannelSftp connect(SessionInfo info, char[] password, Display display) throws Exception {
        try {
            JSch jsch = new JSch();
            SshConnection.applyKnownHosts(jsch);

            if (info.authType == SessionInfo.AuthType.PRIVATE_KEY
                    && info.keyPath != null && !info.keyPath.isBlank()) {
                byte[] passBytes = (password != null && password.length > 0) ? SshConnection.toBytes(password) : null;
                try {
                    jsch.addIdentity(info.keyPath, passBytes);
                } finally {
                    if (passBytes != null) Arrays.fill(passBytes, (byte) 0);
                }
            }

            session = jsch.getSession(info.username, info.host, info.port);
            session.setConfig("StrictHostKeyChecking", "ask");
            session.setUserInfo(new SshConnection.SwtHostVerifier(display, info.host, info.port));
            session.setConfig("ServerAliveInterval", "30");

            if (info.authType == SessionInfo.AuthType.PASSWORD
                    || info.authType == SessionInfo.AuthType.SAVED_CREDENTIAL) {
                byte[] passBytes = (password != null) ? SshConnection.toBytes(password) : new byte[0];
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

            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(15_000);
            return sftp;
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    /** Disconnects the SFTP channel and the whole session. Safe to call even if {@link #connect}
     *  never fully succeeded (e.g. it failed partway through) or was never called at all. */
    public void close() {
        try { if (sftp != null) sftp.disconnect(); } catch (Exception ignored) {}
        try { if (session != null) session.disconnect(); } catch (Exception ignored) {}
    }
}
