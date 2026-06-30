package br.com.quatorzebis.ssh.ssh;

import br.com.quatorzebis.ssh.model.SessionInfo;
import com.jcraft.jsch.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class SshConnection {

    private JSch         jsch;
    private Session      session;
    private ChannelShell channel;
    private InputStream  input;
    private OutputStream output;

    /**
     * @param info     session configuration (no password stored)
     * @param password plaintext password or passphrase; may be null for key auth without passphrase
     */
    public void connect(SessionInfo info, String password) throws Exception {
        jsch = new JSch();

        if (info.authType == SessionInfo.AuthType.PRIVATE_KEY
                && info.keyPath != null && !info.keyPath.isBlank()) {
            String pass = (password == null || password.isBlank()) ? null : password;
            jsch.addIdentity(info.keyPath, pass);
        }

        session = jsch.getSession(info.username, info.host, info.port);
        session.setConfig("StrictHostKeyChecking", "no");
        session.setConfig("ServerAliveInterval",   "30");

        if (info.authType == SessionInfo.AuthType.PASSWORD
                || info.authType == SessionInfo.AuthType.SAVED_CREDENTIAL) {
            session.setPassword(password != null ? password : "");
            session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        } else {
            session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password");
        }

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
}
