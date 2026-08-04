package br.com.capoeirassh.ssh.ui;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * July 2026 security audit, finding #28 (build 140): pasted text must have control bytes
 * stripped before being sent to the remote shell — clipboard content is effectively untrusted
 * input (e.g. copied from a web page or another compromised app) and could otherwise inject
 * escape sequences or other control bytes directly into the SSH session. {@code sanitizePaste()}
 * is pure/static (no SWT state), so this test drives it via reflection with no Display needed.
 */
class TerminalTabSanitizePasteTest {

    private static String sanitize(String s) throws Exception {
        Method m = TerminalTab.class.getDeclaredMethod("sanitizePaste", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, s);
    }

    @Test
    void plainAsciiText_passesThroughUnchanged() throws Exception {
        assertEquals("hello world 123", sanitize("hello world 123"));
    }

    @Test
    void tabIsPreserved() throws Exception {
        assertEquals("a\tb", sanitize("a\tb"));
    }

    @Test
    void crlf_isCollapsedToASingleCr() throws Exception {
        assertEquals("line1\rline2", sanitize("line1\r\nline2"));
    }

    @Test
    void bareLf_isConvertedToCr() throws Exception {
        assertEquals("line1\rline2", sanitize("line1\nline2"));
    }

    @Test
    void bareCr_isPreservedAsCr() throws Exception {
        assertEquals("line1\rline2", sanitize("line1\rline2"));
    }

    @Test
    void escapeByteAndOtherC0Controls_areStripped() throws Exception {
        String withEscAndControls = "before" + (char) 0x1B + "[31m" + (char) 0x01 + (char) 0x02 + "after";
        String result = sanitize(withEscAndControls);
        assertFalse(result.contains(String.valueOf((char) 0x1B)), "ESC must never reach the remote via paste");
        assertEquals("before[31mafter", result, "control bytes stripped, printable text kept");
    }

    @Test
    void delByte_isStripped() throws Exception {
        assertEquals("ab", sanitize("a" + (char) 0x7F + "b"));
    }

    @Test
    void nonAsciiUnicodeCharacters_arePreserved() throws Exception {
        assertEquals("café ✓", sanitize("café ✓"));
    }

    @Test
    void emptyString_returnsEmptyString() throws Exception {
        assertEquals("", sanitize(""));
    }

    @Test
    void mixedRealisticPaste_onlyControlBytesRemoved() throws Exception {
        String input = "curl -sSL " + (char) 0x1B + "https://example.com/x.sh\r\n | bash\n";
        String result = sanitize(input);
        assertFalse(result.chars().anyMatch(c -> c == 0x1B), "no ESC byte must survive");
        assertEquals("curl -sSL https://example.com/x.sh\r | bash\r", result);
    }
}
