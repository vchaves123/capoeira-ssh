package br.com.capoeirassh.ssh.ssh;

import br.com.capoeirassh.ssh.model.TunnelSpec;
import com.jcraft.jsch.JSchException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** What can be verified without a live SSH server: the not-connected behaviour and helpers. */
class TunnelConnectionTest {

    private static TunnelSpec spec(TunnelSpec.Type type, int bind) {
        TunnelSpec t = new TunnelSpec();
        t.type = type; t.bindPort = bind; t.destHost = "h"; t.destPort = 1;
        return t;
    }

    @Test
    void neverConnected_reportsNothingActive() {
        TunnelConnection c = new TunnelConnection();
        TunnelSpec t = spec(TunnelSpec.Type.LOCAL, 8080);
        assertFalse(c.isConnected());
        assertFalse(c.isActive(t));
        assertEquals(0, c.activeCount());
        assertNull(c.errorOf(t));
    }

    @Test
    void start_whenNotConnected_throwsAndRecordsError() {
        TunnelConnection c = new TunnelConnection();
        TunnelSpec t = spec(TunnelSpec.Type.LOCAL, 8080);
        assertThrows(JSchException.class, () -> c.start(t));
        assertEquals("Not connected", c.errorOf(t));
        assertFalse(c.isActive(t));
    }

    @Test
    void stop_closeAreSafeWhenNeverConnected_andIdempotent() {
        TunnelConnection c = new TunnelConnection();
        TunnelSpec t = spec(TunnelSpec.Type.REMOTE, 9000);
        assertDoesNotThrow(() -> c.stop(t));
        assertDoesNotThrow(c::close);
        assertDoesNotThrow(c::close);
    }

    @Test
    void stop_clearsAPreviousError() {
        TunnelConnection c = new TunnelConnection();
        TunnelSpec t = spec(TunnelSpec.Type.LOCAL, 8080);
        assertThrows(JSchException.class, () -> c.start(t));
        assertNotNull(c.errorOf(t));
        c.stop(t);
        assertNull(c.errorOf(t));
    }

    @Test
    void remoteBind_mapsLoopbackToLocalhost_andKeepsEverythingElse() {
        assertEquals("localhost", TunnelConnection.remoteBind("127.0.0.1"));
        assertEquals("localhost", TunnelConnection.remoteBind(" 127.0.0.1 "));
        assertEquals("localhost", TunnelConnection.remoteBind("::1"));
        assertEquals("", TunnelConnection.remoteBind(""));
        assertEquals("", TunnelConnection.remoteBind(null));
        assertEquals("0.0.0.0", TunnelConnection.remoteBind("0.0.0.0"));
        assertEquals("10.1.1.1", TunnelConnection.remoteBind("10.1.1.1"));
    }

    @Test
    void friendly_translatesCommonJschFailures() {
        assertEquals("Port already in use",
            TunnelConnection.friendly(new JSchException("Address already in use: bind")));
        assertTrue(TunnelConnection.friendly(new JSchException("remote port forwarding failed for listen port 80"))
            .startsWith("Server refused"));
        assertEquals("boom", TunnelConnection.friendly(new JSchException("boom")));
        assertEquals("Failed to start", TunnelConnection.friendly(new JSchException("")));
    }
}
