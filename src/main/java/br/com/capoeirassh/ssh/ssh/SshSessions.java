package br.com.capoeirassh.ssh.ssh;

import br.com.capoeirassh.ssh.model.SessionInfo;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.eclipse.swt.widgets.Display;

import java.util.Arrays;

/**
 * Opens a standalone, already-authenticated JSch {@link Session} for a {@link SessionInfo} —
 * shared by every feature that needs its own SSH connection independent of any terminal tab
 * ({@link SftpConnection}, {@link TunnelConnection}).
 */
final class SshSessions {

    private SshSessions() {}

    /**
     * Connects a fresh SSH session to {@code info.host}/{@code info.port}.
     *
     * @param password plaintext password or passphrase as char[] (zeroed before returning, on both
     *                 success and failure); null = no password
     * @param display  SWT display — used to show the host-key verification dialog on the UI thread
     *                 if needed
     */
    static Session open(SessionInfo info, char[] password, Display display) throws Exception {
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

            Session session = jsch.getSession(info.username, info.host, info.port);
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
            return session;
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
    }
}
