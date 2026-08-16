package br.com.capoeirassh.ssh.serial;

import br.com.capoeirassh.ssh.model.SessionInfo;
import com.fazecast.jSerialComm.SerialPort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic coverage for {@link SerialConnection} — mirrors the pattern used for
 * {@code SftpConnection}/{@code SshConnection}: the parts that can be tested without a real
 * serial device (here, the {@link SessionInfo} line-settings enums mapping onto jSerialComm's
 * {@code SerialPort} int constants) get a unit test; actually opening a COM port needs hardware
 * or a loopback rig this suite doesn't have.
 */
class SerialConnectionTest {

    @Test
    void mapStopBits_oneAndTwo() {
        assertEquals(SerialPort.ONE_STOP_BIT, SerialConnection.mapStopBits(1));
        assertEquals(SerialPort.TWO_STOP_BITS, SerialConnection.mapStopBits(2));
    }

    @Test
    void mapStopBits_anyOtherValue_defaultsToOne() {
        // SessionStorage.load() already clamps a corrupted/hand-edited value to 1 or 2 before it
        // ever reaches here — this just documents that mapStopBits() itself fails safe too.
        assertEquals(SerialPort.ONE_STOP_BIT, SerialConnection.mapStopBits(0));
        assertEquals(SerialPort.ONE_STOP_BIT, SerialConnection.mapStopBits(3));
    }

    @Test
    void mapParity_allFiveValues() {
        assertEquals(SerialPort.NO_PARITY,    SerialConnection.mapParity(SessionInfo.SerialParity.NONE));
        assertEquals(SerialPort.ODD_PARITY,   SerialConnection.mapParity(SessionInfo.SerialParity.ODD));
        assertEquals(SerialPort.EVEN_PARITY,  SerialConnection.mapParity(SessionInfo.SerialParity.EVEN));
        assertEquals(SerialPort.MARK_PARITY,  SerialConnection.mapParity(SessionInfo.SerialParity.MARK));
        assertEquals(SerialPort.SPACE_PARITY, SerialConnection.mapParity(SessionInfo.SerialParity.SPACE));
    }

    @Test
    void mapFlowControl_none_isDisabled() {
        assertEquals(SerialPort.FLOW_CONTROL_DISABLED,
                SerialConnection.mapFlowControl(SessionInfo.SerialFlowControl.NONE));
    }

    @Test
    void mapFlowControl_rtsCts_enablesBothRtsAndCts() {
        int flags = SerialConnection.mapFlowControl(SessionInfo.SerialFlowControl.RTS_CTS);
        assertTrue((flags & SerialPort.FLOW_CONTROL_RTS_ENABLED) != 0, "RTS must be enabled");
        assertTrue((flags & SerialPort.FLOW_CONTROL_CTS_ENABLED) != 0, "CTS must be enabled");
    }

    @Test
    void mapFlowControl_xonXoff_enablesBothInAndOut() {
        int flags = SerialConnection.mapFlowControl(SessionInfo.SerialFlowControl.XON_XOFF);
        assertTrue((flags & SerialPort.FLOW_CONTROL_XONXOFF_IN_ENABLED) != 0, "XON/XOFF in must be enabled");
        assertTrue((flags & SerialPort.FLOW_CONTROL_XONXOFF_OUT_ENABLED) != 0, "XON/XOFF out must be enabled");
    }

    @Test
    void isConnected_falseBeforeConnect() {
        assertFalse(new SerialConnection().isConnected(),
                "a connection nobody ever tried to open must report not connected");
    }

    @Test
    void close_withoutEverCallingConnect_doesNotThrow() {
        assertDoesNotThrow(new SerialConnection()::close,
                "close() must be safe even when connect() was never called (no port to close)");
    }

    @Test
    void connect_toANonexistentPort_throwsIOException() {
        SessionInfo info = new SessionInfo();
        info.connectionType = SessionInfo.ConnectionType.SERIAL;
        // Not a real system port name on any platform this test runs on — jSerialComm's
        // getCommPort() never fails for an unknown name (it just wraps whatever string is given),
        // so openPort() is what actually reports failure here.
        info.serialPortName = "NOT-A-REAL-PORT-xyz123";
        info.serialBaudRate = 9600;

        SerialConnection conn = new SerialConnection();
        assertThrows(java.io.IOException.class, () -> conn.connect(info, null, null, null));
        assertFalse(conn.isConnected());
    }
}
