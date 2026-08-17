package br.com.capoeirassh.ssh.serial;

import br.com.capoeirassh.ssh.model.SessionInfo;
import br.com.capoeirassh.ssh.ssh.TerminalConnection;
import com.fazecast.jSerialComm.SerialPort;
import org.eclipse.swt.widgets.Display;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A local RS232 serial connection — {@link br.com.capoeirassh.ssh.ui.TerminalTab}'s counterpart
 * to {@link br.com.capoeirassh.ssh.ssh.SshConnection} for {@link SessionInfo.ConnectionType#SERIAL}
 * sessions. Just opens a COM port with the configured line settings and exposes its raw byte
 * streams — no PTY, no handshake, no credentials, unlike an SSH shell channel.
 */
public class SerialConnection implements TerminalConnection {

    private SerialPort   port;
    private InputStream  input;
    private OutputStream output;

    /**
     * @param password    unused — a serial link has no concept of credentials
     * @param display     unused — opening a COM port never needs a UI-thread prompt
     * @param verboseSink unused — see {@link #setVerbose}
     */
    @Override
    public void connect(SessionInfo info, char[] password, Display display,
                         Consumer<String> verboseSink) throws IOException {
        try {
            port = SerialPort.getCommPort(info.serialPortName);
        } catch (com.fazecast.jSerialComm.SerialPortInvalidPortException e) {
            // On Linux/macOS jSerialComm validates the descriptor format up front and throws this
            // unchecked exception immediately; on Windows it accepts any string here and only
            // fails later, at openPort() below (which already throws IOException). Wrap it so
            // callers see one consistent failure mode on every platform.
            throw new IOException("Could not open serial port " + info.serialPortName
                + " — check that it exists and isn't already in use by another program.", e);
        }
        port.setComPortParameters(
            info.serialBaudRate,
            info.serialDataBits,
            mapStopBits(info.serialStopBits),
            mapParity(info.serialParity));
        port.setFlowControl(mapFlowControl(info.serialFlowControl));
        // Blocking reads with no timeout — mirrors the semantics TerminalTab's reader thread
        // already relies on for an SSH channel's InputStream (a blocking read loop).
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 0, 0);

        if (!port.openPort())
            throw new IOException("Could not open serial port " + info.serialPortName
                + " — check that it exists and isn't already in use by another program.");

        input  = port.getInputStream();
        output = port.getOutputStream();
    }

    /** Synchronized to match {@link br.com.capoeirassh.ssh.ssh.SshConnection#send} — a multi-line
     *  paste is sent line-by-line from a background thread and must not race the UI thread's own
     *  key-typed sends onto the same OutputStream. */
    @Override
    public synchronized void send(byte[] data) throws IOException {
        output.write(data);
        output.flush();
    }

    /** No-op — a serial link has no remote pane whose size to notify; there's no PTY on the
     *  other end of the wire the way there is for an SSH shell channel. */
    @Override
    public void updatePtySize(int cols, int rows, int widthPx, int heightPx) {}

    @Override
    public InputStream getInputStream() { return input; }

    @Override
    public boolean isConnected() { return port != null && port.isOpen(); }

    @Override
    public void close() {
        try { if (port != null) port.closePort(); } catch (Exception ignored) {}
    }

    /** No-op — serial has no key-exchange/auth negotiation to trace the way SSH's {@code -vvv}
     *  verbose mode does. */
    @Override
    public void setVerbose(boolean on) {}

    // -----------------------------------------------------------------------
    // jSerialComm parameter mapping
    // -----------------------------------------------------------------------

    static int mapStopBits(int stopBits) {
        return stopBits == 2 ? SerialPort.TWO_STOP_BITS : SerialPort.ONE_STOP_BIT;
    }

    static int mapParity(SessionInfo.SerialParity parity) {
        return switch (parity) {
            case ODD   -> SerialPort.ODD_PARITY;
            case EVEN  -> SerialPort.EVEN_PARITY;
            case MARK  -> SerialPort.MARK_PARITY;
            case SPACE -> SerialPort.SPACE_PARITY;
            case NONE  -> SerialPort.NO_PARITY;
        };
    }

    static int mapFlowControl(SessionInfo.SerialFlowControl flow) {
        return switch (flow) {
            case RTS_CTS  -> SerialPort.FLOW_CONTROL_RTS_ENABLED | SerialPort.FLOW_CONTROL_CTS_ENABLED;
            case XON_XOFF -> SerialPort.FLOW_CONTROL_XONXOFF_IN_ENABLED | SerialPort.FLOW_CONTROL_XONXOFF_OUT_ENABLED;
            case NONE     -> SerialPort.FLOW_CONTROL_DISABLED;
        };
    }

    /** Lists the system names (e.g. "COM3", "/dev/ttyUSB0") of serial ports currently present on
     *  this machine, for the session dialog's port picker. */
    public static List<String> listPortNames() {
        List<String> names = new ArrayList<>();
        for (SerialPort p : SerialPort.getCommPorts()) names.add(p.getSystemPortName());
        return names;
    }
}
