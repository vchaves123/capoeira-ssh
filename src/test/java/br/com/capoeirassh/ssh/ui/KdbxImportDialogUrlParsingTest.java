package br.com.capoeirassh.ssh.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Exercises {@link KdbxImportDialog#parseUrlToHostPort} — a pure function, no SWT/Display
 *  dependency, same convention as ImportSessionsDialogDedupeTest for isDuplicate(). */
class KdbxImportDialogUrlParsingTest {

    @Test
    void schemeHostPort() {
        var hp = KdbxImportDialog.parseUrlToHostPort("ssh://10.0.0.5:2222");
        assertEquals("10.0.0.5", hp.host());
        assertEquals(2222, hp.port());
    }

    @Test
    void schemeUserHostPort() {
        var hp = KdbxImportDialog.parseUrlToHostPort("ssh://root@prod.example.com:22");
        assertEquals("prod.example.com", hp.host());
        assertEquals(22, hp.port());
    }

    @Test
    void bareHostPortNoScheme() {
        var hp = KdbxImportDialog.parseUrlToHostPort("prod.example.com:2200");
        assertEquals("prod.example.com", hp.host());
        assertEquals(2200, hp.port());
    }

    @Test
    void bareHostNoPortDefaultsTo22() {
        var hp = KdbxImportDialog.parseUrlToHostPort("prod.example.com");
        assertEquals("prod.example.com", hp.host());
        assertEquals(22, hp.port());
    }

    @Test
    void trailingPathIsStripped() {
        var hp = KdbxImportDialog.parseUrlToHostPort("https://example.com:8443/some/path?x=1");
        assertEquals("example.com", hp.host());
        assertEquals(8443, hp.port());
    }

    @Test
    void blankUrlYieldsBlankHostAndDefaultPort() {
        var hp = KdbxImportDialog.parseUrlToHostPort("");
        assertEquals("", hp.host());
        assertEquals(22, hp.port());

        var hp2 = KdbxImportDialog.parseUrlToHostPort(null);
        assertEquals("", hp2.host());
        assertEquals(22, hp2.port());
    }

    @Test
    void invalidPortSuffixFallsBackToWholeStringAsHost() {
        // "99999" is out of the valid port range — treated as part of the host instead of a port.
        var hp = KdbxImportDialog.parseUrlToHostPort("example.com:99999");
        assertEquals("example.com:99999", hp.host());
        assertEquals(22, hp.port());
    }

    @Test
    void bareIpv6LiteralWithoutPortIsKeptWhole() {
        // No confident port split for a bare (unbracketed) IPv6 literal (multiple colons) —
        // kept as-is, matching the "leave it for the user to fix up" fallback.
        var hp = KdbxImportDialog.parseUrlToHostPort("::1");
        assertEquals("::1", hp.host());
        assertEquals(22, hp.port());
    }

    @Test
    void bracketedIpv6WithPort() {
        var hp = KdbxImportDialog.parseUrlToHostPort("[::1]:2222");
        assertEquals("::1", hp.host());
        assertEquals(2222, hp.port());
    }

    @Test
    void bracketedIpv6WithoutPortDefaultsTo22() {
        var hp = KdbxImportDialog.parseUrlToHostPort("[fe80::1]");
        assertEquals("fe80::1", hp.host());
        assertEquals(22, hp.port());
    }
}
