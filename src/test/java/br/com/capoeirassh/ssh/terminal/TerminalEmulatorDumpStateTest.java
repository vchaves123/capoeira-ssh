package br.com.capoeirassh.ssh.terminal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TerminalEmulator#dumpState()} — the state snapshot written into a byte trace so
 * a later analysis can check the emulator was actually in the state the received bytes should have
 * put it in.
 *
 * <p>The whole value of the snapshot rests on it being <em>faithful</em>: a field that silently
 * reports a stale or wrong value would send an investigation chasing a bug that isn't there. So
 * these tests drive the emulator with real escape sequences and then assert the snapshot reflects
 * what those sequences did, rather than asserting against the fields directly.
 */
class TerminalEmulatorDumpStateTest {

    private static TerminalEmulator emu(int cols, int rows) { return new TerminalEmulator(cols, rows); }

    private static void feed(TerminalEmulator e, String s) {
        e.processBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    /** Crude field extractor — enough to assert on a flat one-line JSON without pulling in a parser. */
    private static String field(String json, String name) {
        int i = json.indexOf("\"" + name + "\":");
        assertTrue(i >= 0, "field \"" + name + "\" missing from snapshot: " + json);
        int start = i + name.length() + 3;
        int depth = 0;
        for (int p = start; p < json.length(); p++) {
            char c = json.charAt(p);
            if (c == '[' || c == '{') depth++;
            else if (c == ']' || c == '}') { if (depth == 0) return json.substring(start, p); depth--; }
            else if (c == ',' && depth == 0) return json.substring(start, p);
        }
        return json.substring(start);
    }

    @Test
    @DisplayName("snapshot of a fresh emulator reports its dimensions and default modes")
    void freshEmulator_reportsDimensionsAndDefaults() {
        String s = emu(80, 24).dumpState();

        assertEquals("80", field(s, "cols"));
        assertEquals("24", field(s, "rows"));
        assertEquals("[0,0]", field(s, "cursor"));
        assertEquals("\"primary\"", field(s, "active"));
        assertEquals("false", field(s, "altBufferActive"));
        assertEquals("[0,23]", field(s, "scroll"));
        // DECAWM is set at power-on, DECOM is not — a snapshot that got these backwards would
        // make correct wrapping behavior look like a bug.
        assertEquals("true",  field(s, "autoWrap"));
        assertEquals("false", field(s, "originMode"));
        assertEquals("true",  field(s, "cursorVisible"));
        assertEquals("0", field(s, "scrollbackLines"));
    }

    @Test
    @DisplayName("written text appears in the active buffer's text rows at the right column")
    void writtenText_appearsInBufferText() {
        TerminalEmulator e = emu(10, 3);
        feed(e, "hi");

        String rows = field(e.dumpState(), "primary");
        // Trailing spaces are preserved: a cleared cell must stay distinguishable from a written
        // space, otherwise "did the app clear this line?" becomes unanswerable from the snapshot.
        assertTrue(rows.contains("\"hi        \""),
                "first row should be the written text padded to the full width, got: " + rows);
    }

    @Test
    @DisplayName("cursor position in the snapshot follows a CUP sequence")
    void cursorPosition_followsCup() {
        TerminalEmulator e = emu(80, 24);
        feed(e, "\033[5;10H");   // CUP row 5, col 10 (1-based on the wire, 0-based internally)
        assertEquals("[4,9]", field(e.dumpState(), "cursor"));
    }

    @Test
    @DisplayName("alternate screen switch is reported, and the primary buffer keeps its content")
    void altScreenSwitch_reportedAndPrimaryPreserved() {
        TerminalEmulator e = emu(10, 2);
        feed(e, "primary");
        feed(e, "\033[?1049h");  // enter alternate screen
        feed(e, "alt");

        String s = e.dumpState();
        assertEquals("true",        field(s, "altBufferActive"));
        assertEquals("\"alternate\"", field(s, "active"));

        // This is the property the snapshot exists to verify: entering the alt screen must not
        // destroy the primary buffer, since leaving it has to restore exactly what was there.
        assertTrue(field(s, "primary").contains("\"primary   \""),
                "primary buffer content must survive the switch to the alternate screen");
        assertTrue(field(s, "alternate").contains("\"alt       \""),
                "alternate buffer should hold what was written after the switch");
    }

    @Test
    @DisplayName("leaving the alternate screen restores the primary buffer as the active one")
    void leavingAltScreen_restoresPrimary() {
        TerminalEmulator e = emu(10, 2);
        feed(e, "primary");
        feed(e, "\033[?1049h");
        feed(e, "alt");
        feed(e, "\033[?1049l");

        String s = e.dumpState();
        assertEquals("false",       field(s, "altBufferActive"));
        assertEquals("\"primary\"", field(s, "active"));
        assertTrue(field(s, "primary").contains("\"primary   \""));
    }

    @Test
    @DisplayName("SGR attributes are run-length encoded, defaults omitted")
    void attributes_areRunLengthEncoded() {
        TerminalEmulator e = emu(6, 1);
        feed(e, "\033[1mAB\033[0mCD");   // bold "AB", default "CD", then 2 untouched cells

        String attrs = field(e.dumpState(), "primary");
        // Bold run first, then everything else collapses into default runs — an untouched cell
        // costs "{}" rather than a full attribute object.
        assertTrue(attrs.contains("[0,1,{\"bold\":true}]"),
                "columns 0-1 should be one bold run, got: " + attrs);
        assertTrue(attrs.contains("[2,5,{}]"),
                "columns 2-5 share the default attributes and should collapse to one run, got: " + attrs);
    }

    @Test
    @DisplayName("colors are reported and a color change splits the run")
    void colorChange_splitsRun() {
        TerminalEmulator e = emu(4, 1);
        feed(e, "\033[31mR\033[32mG\033[0m");

        String attrs = field(e.dumpState(), "primary");
        assertTrue(attrs.contains("[0,0,{\"fg\":1}]"), "red cell should report fg 1, got: " + attrs);
        assertTrue(attrs.contains("[1,1,{\"fg\":2}]"), "green cell should report fg 2, got: " + attrs);
    }

    @Test
    @DisplayName("scroll region set by DECSTBM is reported")
    void scrollRegion_isReported() {
        TerminalEmulator e = emu(80, 24);
        feed(e, "\033[5;20r");   // DECSTBM rows 5-20 (1-based)
        assertEquals("[4,19]", field(e.dumpState(), "scroll"));
    }

    @Test
    @DisplayName("tab stops are reported as the columns that actually carry a stop")
    void tabStops_areReported() {
        TerminalEmulator e = emu(24, 1);
        feed(e, "\033[3g");      // TBC 3 — clear all stops
        feed(e, "\033[1;5H");    // move to column 5 (1-based)
        feed(e, "\033H");        // HTS — set a stop here

        assertEquals("[4]", field(e.dumpState(), "tabStops"),
                "after clearing all stops and setting one at column 4, only that stop should remain");
    }

    @Test
    @DisplayName("bracketed paste and application cursor key modes are reported")
    void terminalModes_areReported() {
        TerminalEmulator e = emu(80, 24);
        feed(e, "\033[?2004h");  // bracketed paste on
        feed(e, "\033[?1h");     // application cursor keys on

        String s = e.dumpState();
        assertEquals("true", field(s, "bracketedPaste"));
        assertEquals("true", field(s, "appCursorKeys"));
    }

    @Test
    @DisplayName("a double quote on screen is escaped so the snapshot stays parseable")
    void quoteInContent_isEscaped() {
        TerminalEmulator e = emu(4, 1);
        feed(e, "a\"b");

        String s = e.dumpState();
        assertTrue(s.contains("a\\\"b"),
                "a literal quote in terminal content must be escaped, or the JSON breaks: " + s);
    }

    @Test
    @DisplayName("a backslash on screen is escaped")
    void backslashInContent_isEscaped() {
        TerminalEmulator e = emu(4, 1);
        feed(e, "a\\b");
        assertTrue(e.dumpState().contains("a\\\\b"), "a literal backslash must be escaped");
    }

    @Test
    @DisplayName("a non-BMP character survives the snapshot as a surrogate pair")
    void astralCharacter_isEncoded() {
        TerminalEmulator e = emu(4, 1);
        feed(e, "😀");   // U+1F600 grinning face

        assertTrue(e.dumpState().contains("😀"),
                "an astral code point (emoji, Nerd Font icon) must not be lost or mangled");
    }

    @Test
    @DisplayName("snapshot is a single line, so trace records stay one-per-line")
    void snapshot_isSingleLine() {
        TerminalEmulator e = emu(20, 5);
        feed(e, "line one\r\nline two");

        String s = e.dumpState();
        assertFalse(s.contains("\n"), "a raw newline would split one STATE record across trace lines");
        assertFalse(s.contains("\r"), "a raw carriage return would corrupt the trace line format");
    }

    @Test
    @DisplayName("both buffers are always present, even before the alternate screen is ever used")
    void bothBuffers_alwaysPresent() {
        String s = emu(10, 2).dumpState();
        assertFalse(field(s, "primary").isEmpty());
        assertFalse(field(s, "alternate").isEmpty());
    }
}
