package br.com.capoeirassh.ssh;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code UpdateChecker} had zero tests despite two nontrivial, hand-rolled pieces of logic:
 * a character-by-character JSON string extractor (deliberately not regex-based — see its own
 * javadoc for the {@code StackOverflowError} history) and a dotted-version comparator. Both are
 * pure functions, testable without any real network call.
 */
class UpdateCheckerTest {

    private static String extractJsonString(String json, String key) throws Exception {
        Method m = UpdateChecker.class.getDeclaredMethod("extractJsonString", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, json, key);
    }

    // -----------------------------------------------------------------------
    // extractJsonString
    // -----------------------------------------------------------------------

    @Test
    void extractJsonString_findsASimpleStringValue() throws Exception {
        String json = "{\"tag_name\":\"v1.6.0\",\"other\":1}";
        assertEquals("v1.6.0", extractJsonString(json, "tag_name"));
    }

    @Test
    void extractJsonString_returnsNull_whenKeyNotPresent() throws Exception {
        String json = "{\"tag_name\":\"v1.6.0\"}";
        assertNull(extractJsonString(json, "html_url"));
    }

    @Test
    void extractJsonString_returnsNull_forAnUnterminatedString() throws Exception {
        // e.g. truncated by MAX_RESPONSE_BYTES mid-value
        String json = "{\"body\":\"this never closes";
        assertNull(extractJsonString(json, "body"));
    }

    @Test
    void extractJsonString_decodesCommonEscapeSequences() throws Exception {
        String json = "{\"body\":\"line1\\nline2\\ttabbed\\r\\\"quoted\\\"\\\\backslash\"}";
        assertEquals("line1\nline2\ttabbed\r\"quoted\"\\backslash", extractJsonString(json, "body"));
    }

    @Test
    void extractJsonString_decodesUnicodeEscapes() throws Exception {
        String json = "{\"body\":\"caf\\u00e9\"}"; // "café"
        assertEquals("café", extractJsonString(json, "body"));
    }

    @Test
    void extractJsonString_handlesMultipleKeys_findingTheRightOne() throws Exception {
        String json = "{\"tag_name\":\"v1.6.0\",\"html_url\":\"https://example.com/r/1\",\"body\":\"Notes here\"}";
        assertEquals("v1.6.0", extractJsonString(json, "tag_name"));
        assertEquals("https://example.com/r/1", extractJsonString(json, "html_url"));
        assertEquals("Notes here", extractJsonString(json, "body"));
    }

    @Test
    void extractJsonString_forwardSlashEscape_isUnescaped() throws Exception {
        String json = "{\"html_url\":\"https:\\/\\/example.com\\/releases\\/latest\"}";
        assertEquals("https://example.com/releases/latest", extractJsonString(json, "html_url"));
    }

    // -----------------------------------------------------------------------
    // isNewer — dotted numeric version comparison
    // -----------------------------------------------------------------------

    @Test
    void isNewer_higherPatchVersion_isNewer() {
        assertTrue(UpdateChecker.isNewer("1.0.10", "1.0.9"));
    }

    @Test
    void isNewer_higherMinorVersion_isNewer() {
        assertTrue(UpdateChecker.isNewer("1.6.0", "1.5.9"));
    }

    @Test
    void isNewer_sameVersion_isNotNewer() {
        assertFalse(UpdateChecker.isNewer("1.5.0", "1.5.0"));
    }

    @Test
    void isNewer_lowerVersion_isNotNewer() {
        assertFalse(UpdateChecker.isNewer("1.4.9", "1.5.0"));
    }

    @Test
    void isNewer_differentSegmentCounts_missingSegmentsTreatedAsZero() {
        assertTrue(UpdateChecker.isNewer("1.5.1", "1.5"));   // 1.5 == 1.5.0
        assertFalse(UpdateChecker.isNewer("1.5", "1.5.0"));  // equal, not newer
        assertTrue(UpdateChecker.isNewer("2", "1.9.9"));
    }

    @Test
    void isNewer_nonNumericGarbageInASegment_treatedAsZero_doesNotThrow() {
        assertFalse(assertDoesNotThrow(() -> UpdateChecker.isNewer("1.x.0", "1.0.0")),
                "a non-numeric segment must be treated as 0, not throw NumberFormatException");
    }
}
