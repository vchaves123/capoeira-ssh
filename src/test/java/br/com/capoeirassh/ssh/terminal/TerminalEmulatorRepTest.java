package br.com.capoeirassh.ssh.terminal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REP (CSI n b, "repeat the preceding graphic character"). ncurses emits it under
 * TERM=xterm-256color for long runs of one character, so a terminal that drops it shows only the
 * first character of every rule, frame line and bar — as nmon's screens did.
 */
class TerminalEmulatorRepTest {

    private static final String ESC = "\u001b";

    private static void send(TerminalEmulator emu, String seq) {
        emu.processBytes(seq.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String rowText(TerminalEmulator emu, int row, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int c = from; c < to; c++) sb.appendCodePoint(emu.getCell(row, c, 0).character);
        return sb.toString();
    }

    @Test
    @DisplayName("REP repeats the preceding character n more times")
    void rep_repeatsPrecedingCharacter() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, "-" + ESC + "[9b");
        assertEquals("----------", rowText(emu, 0, 0, 10), "one '-' plus nine repeats");
        assertEquals(10, emu.getCursorCol());
        assertNotEquals('-', emu.getCell(0, 10, 0).character, "must not write past n");
    }

    @Test
    @DisplayName("REP with no parameter or 0 repeats once")
    void rep_defaultCountIsOne() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, "x" + ESC + "[b");
        assertEquals("xx", rowText(emu, 0, 0, 2));
        send(emu, ESC + "[0b");
        assertEquals("xxx", rowText(emu, 0, 0, 3));
    }

    @Test
    @DisplayName("REP repeats the line-drawing glyph while the ACS charset is selected")
    void rep_repeatsLineDrawingGlyph() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "(0" + "q" + ESC + "[4b");   // smacs, 'q' (horizontal line), repeat 4
        assertEquals("─────", rowText(emu, 0, 0, 5));
    }

    @Test
    @DisplayName("REP wraps onto the next line like ordinary output")
    void rep_wrapsAtRightMargin() {
        TerminalEmulator emu = new TerminalEmulator(10, 5);
        send(emu, "=" + ESC + "[14b");               // 15 chars on a 10-column screen
        assertEquals("==========", rowText(emu, 0, 0, 10));
        assertEquals("=====", rowText(emu, 1, 0, 5));
        assertEquals(1, emu.getCursorRow());
    }

    @Test
    @DisplayName("REP with nothing printed yet is ignored")
    void rep_withoutPrecedingCharacter_isIgnored() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[5b");
        assertEquals(0, emu.getCursorCol());
        assertEquals(0, emu.getCursorRow());
    }

    @Test
    @DisplayName("REP is forgotten after a full reset (RIS)")
    void rep_forgottenAfterReset() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, "z" + ESC + "c" + ESC + "[5b");
        assertEquals(0, emu.getCursorCol(), "stale character from before RIS must not be repeated");
    }

    @Test
    @DisplayName("An absurd REP count is capped and returns promptly")
    void rep_hugeCountIsCapped() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, "a");
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
            () -> send(emu, ESC + "[999999999b"));
        assertEquals(24 - 1, emu.getCursorRow(), "a screenful of repeats scrolls to the last row");
    }
}
