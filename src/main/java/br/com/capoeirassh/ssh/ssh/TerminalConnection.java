package br.com.capoeirassh.ssh.ssh;

import br.com.capoeirassh.ssh.model.SessionInfo;
import org.eclipse.swt.widgets.Display;

import java.io.IOException;
import java.io.InputStream;

/**
 * Transport a {@link br.com.capoeirassh.ssh.ui.TerminalTab} pushes bytes through and reads bytes
 * back from — the terminal emulator itself doesn't know or care whether they came from an SSH
 * shell channel or a local serial port. Implemented by {@link SshConnection} (network) and
 * {@link br.com.capoeirassh.ssh.serial.SerialConnection} (local RS232).
 */
public interface TerminalConnection {

    /**
     * @param info        session configuration
     * @param password    plaintext password or passphrase as char[], zeroed after use;
     *                    null/empty and ignored entirely by transports that need no credentials
     *                    (e.g. serial)
     * @param display     SWT display — used by transports that may need to show a UI-thread
     *                    dialog during connect (e.g. SSH host-key verification); unused otherwise
     * @param verboseSink receives one formatted diagnostic line at a time during connect, when
     *                    the transport supports it and the session has it enabled; ignored
     *                    (never called) otherwise
     */
    void connect(SessionInfo info, char[] password, Display display,
                 java.util.function.Consumer<String> verboseSink) throws Exception;

    /** Sends raw bytes to the remote/device. Must be safe to call from any thread. */
    void send(byte[] data) throws IOException;

    /** Notifies the transport of the terminal's current size in columns/rows (and, for a PTY-
     *  backed transport, pixel dimensions too). A no-op for transports with no notion of a
     *  resizable remote pane (e.g. serial). */
    void updatePtySize(int cols, int rows, int widthPx, int heightPx);

    /** The stream {@link br.com.capoeirassh.ssh.ui.TerminalTab}'s reader thread pulls incoming
     *  bytes from. Valid only after a successful {@link #connect}. */
    InputStream getInputStream();

    boolean isConnected();

    /** Releases the underlying connection/handle. Safe to call more than once. */
    void close();

    /** Turns verbose diagnostic output on/off for a transport that supports it; a no-op otherwise. */
    void setVerbose(boolean on);
}
