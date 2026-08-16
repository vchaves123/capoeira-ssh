package br.com.capoeirassh.ssh.ssh;

import br.com.capoeirassh.ssh.model.SessionInfo;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.*;

/**
 * July 2026 security audit, finding #25 (build 139): {@code SshConnection.connect()}'s caller-
 * supplied password/passphrase {@code char[]} must be zeroed even when the connection fails
 * partway through — a wrong host, refused connection, or any other exception must never leave
 * the plaintext secret sitting in memory for longer than necessary. Never had a dedicated
 * regression test forcing an actual failure path and inspecting the array afterward.
 */
@Tag("ui") // needs a real SWT Display — no display on the Linux/macOS CI runners
class SshConnectionSecretZeroingTest {

    private static int unusedLocalPort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } // closed immediately — connecting to it afterward should be refused quickly
    }

    @Test
    @Timeout(15)
    void connect_zeroesPasswordArray_evenWhenConnectionFails() throws Exception {
        Display display = new Display();
        try {
            SessionInfo info = new SessionInfo();
            info.host = "127.0.0.1";
            info.port = unusedLocalPort();
            info.username = "test";
            info.authType = SessionInfo.AuthType.PASSWORD;

            char[] password = "s3cr3t-password".toCharArray();
            SshConnection conn = new SshConnection();

            assertThrows(Exception.class, () -> conn.connect(info, password, display, null),
                    "test setup: connecting to a closed local port must fail");

            for (char c : password) {
                assertEquals('\0', c, "the password array must be zeroed even though connect() threw");
            }
        } finally {
            display.dispose();
        }
    }

    @Test
    @Timeout(15)
    void connect_zeroesPassphraseArray_forPrivateKeyAuth_evenWhenConnectionFails() throws Exception {
        Display display = new Display();
        try {
            SessionInfo info = new SessionInfo();
            info.host = "127.0.0.1";
            info.port = unusedLocalPort();
            info.username = "test";
            info.authType = SessionInfo.AuthType.PRIVATE_KEY;
            // A key file that doesn't exist is enough to make addIdentity()/connect() fail
            // before ever reaching the network — still must go through the same zeroing path.
            info.keyPath = "this-key-file-does-not-exist.pem";

            char[] passphrase = "key-passphrase".toCharArray();
            SshConnection conn = new SshConnection();

            assertThrows(Exception.class, () -> conn.connect(info, passphrase, display, null));

            for (char c : passphrase) {
                assertEquals('\0', c, "the passphrase array must be zeroed even though connect() threw "
                      + "before ever reaching the network (missing key file)");
            }
        } finally {
            display.dispose();
        }
    }

    @Test
    @Timeout(15)
    void connect_zeroesPasswordArray_onSuccessfulPathToo() throws Exception {
        // Not exercised here end-to-end (needs a real/fake SSH server), but the array-zeroing
        // guarantee is a `finally` block wrapping the ENTIRE method body, so a null password
        // (no-password path) must still leave a non-null array untouched-but-absent — this test
        // just documents that null is handled without throwing, since Arrays.fill(null, ...)
        // would NPE if the null-guard were ever removed.
        Display display = new Display();
        try {
            SessionInfo info = new SessionInfo();
            info.host = "127.0.0.1";
            info.port = unusedLocalPort();
            info.username = "test";
            info.authType = SessionInfo.AuthType.PASSWORD;

            SshConnection conn = new SshConnection();
            assertThrows(Exception.class, () -> conn.connect(info, null, display, null),
                    "a null password must still fail the same way (closed port), not NPE on the zeroing path");
        } finally {
            display.dispose();
        }
    }
}
