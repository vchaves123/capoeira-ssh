package br.com.capoeirassh.ssh.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TunnelSpecTest {

    private static TunnelSpec local(int bind, String dest, int destPort) {
        TunnelSpec t = new TunnelSpec();
        t.type = TunnelSpec.Type.LOCAL;
        t.bindPort = bind; t.destHost = dest; t.destPort = destPort;
        return t;
    }

    @Test
    void validate_acceptsATypicalLocalTunnel() {
        assertNull(local(5432, "db.internal", 5432).validate());
    }

    @Test
    void validate_rejectsOutOfRangePorts() {
        assertNotNull(local(0, "db", 5432).validate());
        assertNotNull(local(70000, "db", 5432).validate());
        assertNotNull(local(5432, "db", 0).validate());
        assertNotNull(local(5432, "db", 65536).validate());
        assertNull(local(1, "db", 65535).validate());
    }

    @Test
    void validate_rejectsBlankOrUnsafeDestinationHost() {
        assertNotNull(local(5432, "", 5432).validate());
        assertNotNull(local(5432, "   ", 5432).validate());
        assertNotNull(local(5432, "db host", 5432).validate(), "whitespace must be rejected");
        assertNotNull(local(5432, "db;rm", 5432).validate());
        assertNotNull(local(5432, "d\nb", 5432).validate(), "control characters inside must be rejected");
    }

    @Test
    void validate_acceptsIpv4Ipv6AndHostnames() {
        assertNull(local(80, "10.0.0.5", 80).validate());
        assertNull(local(80, "::1", 80).validate());
        assertNull(local(80, "[::1]", 80).validate());
        assertNull(local(80, "my-host.example.com", 80).validate());
    }

    @Test
    void validate_remoteBindMayBeEmpty_meaningAllInterfaces() {
        TunnelSpec t = new TunnelSpec();
        t.type = TunnelSpec.Type.REMOTE;
        t.bindHost = "";
        t.bindPort = 9000; t.destHost = "127.0.0.1"; t.destPort = 3000;
        assertNull(t.validate());
    }

    @Test
    void key_distinguishesTypeAndBindPort() {
        TunnelSpec l = local(8080, "a", 1);
        TunnelSpec r = local(8080, "a", 1); r.type = TunnelSpec.Type.REMOTE;
        assertNotEquals(l.key(), r.key(), "a local and a remote tunnel may share a port number");
        assertEquals(l.key(), local(8080, "other", 9).key(), "same type + bind port is the same tunnel slot");
    }

    @Test
    void validateAll_rejectsDuplicateBindPortOfSameType() {
        assertNotNull(TunnelSpec.validateAll(List.of(local(8080, "a", 1), local(8080, "b", 2))));
        assertNull(TunnelSpec.validateAll(List.of(local(8080, "a", 1), local(8081, "b", 2))));
    }

    @Test
    void validateAll_propagatesAnInvalidEntry() {
        assertNotNull(TunnelSpec.validateAll(List.of(local(8080, "a", 1), local(0, "b", 2))));
    }

    @Test
    void isLoopbackBind_recognisesLoopbackForms() {
        for (String h : new String[] { "127.0.0.1", "localhost", "LOCALHOST", "::1", "[::1]" }) {
            TunnelSpec t = local(1, "a", 1); t.bindHost = h;
            assertTrue(t.isLoopbackBind(), h);
        }
        for (String h : new String[] { "0.0.0.0", "192.168.1.5", "", "*" }) {
            TunnelSpec t = local(1, "a", 1); t.bindHost = h;
            assertFalse(t.isLoopbackBind(), h);
        }
    }

    @Test
    void copy_isIndependent() {
        TunnelSpec a = local(8080, "a", 1);
        a.description = "x"; a.autoStart = true;
        TunnelSpec b = a.copy();
        assertEquals(a, b);
        b.destHost = "z";
        assertEquals("a", a.destHost);
    }
}
