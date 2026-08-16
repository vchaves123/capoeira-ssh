package br.com.capoeirassh.ssh.ssh;

import br.com.capoeirassh.ssh.model.SessionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code SftpConnection} — the standalone SSH connection each upload/download uses, independent
 * of the terminal tab's own session (see the reconnect bug this replaced: reusing the tab's
 * session let cancelling/finishing a transfer disconnect a *different*, newer session if the tab
 * had reconnected in the meantime). Had no test of its own.
 *
 * <p>Mirrors {@code SshConnectionSecretZeroingTest}'s pattern — a closed local port fails fast
 * and deterministically, no real/fake SSH server needed — plus the one property specific to this
 * class: {@link SftpConnection#close} must be safe to call no matter how far {@code connect()}
 * got, since every upload/download call site calls it unconditionally on the way out.
 */
class SftpConnectionTest {

    private static int unusedLocalPort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } // closed immediately — connecting to it afterward should be refused quickly
    }

    @Test
    @Timeout(15)
    void close_afterFailedConnect_doesNotThrow() throws Exception {
        SessionInfo info = new SessionInfo();
        info.host = "127.0.0.1";
        info.port = unusedLocalPort();
        info.username = "test";
        info.authType = SessionInfo.AuthType.PASSWORD;

        SftpConnection conn = new SftpConnection();
        assertThrows(Exception.class, () -> conn.connect(info, "s3cr3t".toCharArray(), null),
                "test setup: connecting to a closed local port must fail");

        assertDoesNotThrow(conn::close,
                "close() must be safe even when connect() never got past opening the session "
              + "(no session, no sftp channel to disconnect)");
    }

    @Test
    @Timeout(15)
    void close_withoutEverCallingConnect_doesNotThrow() {
        assertDoesNotThrow(new SftpConnection()::close,
                "a connection nobody ever tried to open must still be safe to close");
    }

    @Test
    @Timeout(15)
    void connect_zeroesPasswordArray_evenWhenConnectionFails() throws Exception {
        SessionInfo info = new SessionInfo();
        info.host = "127.0.0.1";
        info.port = unusedLocalPort();
        info.username = "test";
        info.authType = SessionInfo.AuthType.PASSWORD;

        char[] password = "s3cr3t-password".toCharArray();
        SftpConnection conn = new SftpConnection();

        assertThrows(Exception.class, () -> conn.connect(info, password, null));

        for (char c : password) {
            assertEquals('\0', c, "the password array must be zeroed even though connect() threw");
        }
    }

    @Test
    @Timeout(15)
    void connect_zeroesPassphraseArray_forPrivateKeyAuth_evenWhenConnectionFails() throws Exception {
        SessionInfo info = new SessionInfo();
        info.host = "127.0.0.1";
        info.port = unusedLocalPort();
        info.username = "test";
        info.authType = SessionInfo.AuthType.PRIVATE_KEY;
        // A key file that doesn't exist is enough to make addIdentity()/connect() fail before
        // ever reaching the network — still must go through the same zeroing path.
        info.keyPath = "this-key-file-does-not-exist.pem";

        char[] passphrase = "key-passphrase".toCharArray();
        SftpConnection conn = new SftpConnection();

        assertThrows(Exception.class, () -> conn.connect(info, passphrase, null));

        for (char c : passphrase) {
            assertEquals('\0', c, "the passphrase array must be zeroed even though connect() threw "
                  + "before ever reaching the network (missing key file)");
        }
    }

    /** Not itself proof of the original reconnect bug (that needs a real/fake SSH server to
     *  drive two live sessions at once) — this documents the structural property that actually
     *  fixes it: an SftpConnection never touches an SshConnection at all, so nothing it does can
     *  reach across and disconnect a terminal tab's session. */
    @Test
    void sftpConnection_hasNoReferenceToSshConnection() {
        for (var field : SftpConnection.class.getDeclaredFields()) {
            assertNotEquals(SshConnection.class, field.getType(),
                    "SftpConnection must stay fully independent of SshConnection — sharing a "
                  + "reference here is exactly what caused the reconnect-kills-transfer bug");
        }
    }
}
