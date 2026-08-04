package br.com.capoeirassh.ssh.storage;

import br.com.capoeirassh.ssh.model.SessionInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code SessionImporter.fromMobaXtermIni}'s SSH-bookmark line parser used to call
 * {@code value.split("%")} with no limit, even though only {@code fields[1..3]} (host, port,
 * username) are ever read. A single adversarial or corrupted {@code .ini} line packed with '%'
 * characters — still well within the file's own 20MB size cap ({@code MAX_IMPORT_FILE_BYTES}) —
 * forces {@code String.split} to allocate one array element (a full {@code String} object) per
 * delimiter occurrence, a memory amplification wildly out of proportion to the handful of fields
 * actually needed. (An earlier version of this test tried to observe this via wall-clock timing;
 * that turned out to be unreliable — even ~19 million delimiters, near the file's own size cap,
 * still split in well under half a second on modern hardware, so no dramatic slowdown was
 * observable at any input size the existing cap allows.) This test instead asserts the fix's
 * actual, deterministic effect directly: the array size returned by the extracted, private
 * {@code splitBookmarkFields} helper.
 */
class SessionImporterMobaXtermSplitAmplificationTest {

    private static String[] splitBookmarkFields(String value) throws Exception {
        Method m = SessionImporter.class.getDeclaredMethod("splitBookmarkFields", String.class);
        m.setAccessible(true);
        return (String[]) m.invoke(null, value);
    }

    @Test
    void splitBookmarkFields_capsArraySize_regardlessOfDelimiterCount() throws Exception {
        // The trailing '%' run must end in a non-'%' character: String.split(regex) with no
        // limit (limit=0) silently discards *trailing* empty strings, so a run of '%' with
        // nothing after it would collapse back down to a small array by accident, masking the
        // very amplification this test needs to catch — appending "x" keeps every empty segment
        // from the '%' run interior (not trailing), so none of them get silently dropped.
        StringBuilder value = new StringBuilder("#109#0%host.example.com%22%alice");
        for (int i = 0; i < 100_000; i++) value.append('%');
        value.append('x');

        String[] fields = splitBookmarkFields(value.toString());

        assertTrue(fields.length <= 6,
                "splitBookmarkFields() returned " + fields.length + " elements for a line with "
              + "100,000 '%' characters — expected a small, capped array regardless of how many "
              + "delimiters the (corrupted/hostile) input line contains");
        // The fields the parser actually reads must still come through correctly.
        assertEquals("#109#0", fields[0]);
        assertEquals("host.example.com", fields[1]);
        assertEquals("22", fields[2]);
        assertEquals("alice", fields[3]);
    }

    @Test
    void fromMobaXtermIni_denseDelimiterLine_stillParsesCorrectly() throws Exception {
        StringBuilder line = new StringBuilder("#109#0%host.example.com%22%alice%");
        for (int i = 0; i < 100_000; i++) line.append('%');

        String content = "[Bookmarks]\nMySession=" + line + "\n";
        Path tmp = Files.createTempFile("mobaxterm-dense-delimiter-test", ".ini");
        try {
            Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
            List<SessionInfo> result = SessionImporter.fromMobaXtermIni(tmp);
            assertEquals(1, result.size());
            assertEquals("host.example.com", result.get(0).host);
            assertEquals(22, result.get(0).port);
            assertEquals("alice", result.get(0).username);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
