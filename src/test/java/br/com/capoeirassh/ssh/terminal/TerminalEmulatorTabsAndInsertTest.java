package br.com.capoeirassh.ssh.terminal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** CHT (CSI n I), CBT (CSI n Z) and IRM (CSI 4 h / CSI 4 l). */
class TerminalEmulatorTabsAndInsertTest {

    private static final String ESC = "\u001b";

    private static void send(TerminalEmulator emu, String seq) {
        emu.processBytes(seq.getBytes(StandardCharsets.UTF_8));
    }

    private static String row(TerminalEmulator emu, int r, int n) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < n; c++) sb.appendCodePoint(emu.getCell(r, c, 0).character);
        return sb.toString();
    }

    // ── CHT / CBT ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("CHT moves forward n default tab stops")
    void cht_forward() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[I");
        assertEquals(8, emu.getCursorCol());
        send(emu, ESC + "[2I");
        assertEquals(24, emu.getCursorCol());
    }

    @Test
    @DisplayName("CBT moves back n tab stops, from between stops and from on a stop")
    void cbt_back() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[1;21H");            // column 20 (between 16 and 24)
        send(emu, ESC + "[Z");
        assertEquals(16, emu.getCursorCol());
        send(emu, ESC + "[2Z");               // from a stop: 16 -> 8 -> 0
        assertEquals(0, emu.getCursorCol());
    }

    @Test
    @DisplayName("CBT at column 0, or past the first stop, stays at column 0")
    void cbt_stopsAtColumnZero() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[1;5H" + ESC + "[9Z");
        assertEquals(0, emu.getCursorCol());
        send(emu, ESC + "[Z");
        assertEquals(0, emu.getCursorCol());
    }

    @Test
    @DisplayName("CBT honours custom tab stops set with HTS after clearing them with TBC")
    void cbt_customStops() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[3g");               // clear all stops
        send(emu, ESC + "[1;11H" + ESC + "H");  // stop at column 10
        send(emu, ESC + "[1;31H" + ESC + "[Z");
        assertEquals(10, emu.getCursorCol());
    }

    @Test
    @DisplayName("A huge count returns promptly")
    void hugeCounts_capped() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
            () -> send(emu, ESC + "[999999999I" + ESC + "[999999999Z"));
        assertEquals(0, emu.getCursorCol());
    }

    // ── IRM ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("In insert mode, printing shifts the rest of the line right")
    void irm_insertsInsteadOfOverwriting() {
        TerminalEmulator emu = new TerminalEmulator(20, 5);
        send(emu, "abcdef" + ESC + "[1;3H" + ESC + "[4h" + "XY");
        assertEquals("abXYcdef", row(emu, 0, 8));
        assertEquals(4, emu.getCursorCol());
    }

    @Test
    @DisplayName("Reset mode (CSI 4 l) goes back to overwriting")
    void irm_resetOverwrites() {
        TerminalEmulator emu = new TerminalEmulator(20, 5);
        send(emu, "abcdef" + ESC + "[4h" + ESC + "[4l" + ESC + "[1;1H" + "Z");
        assertEquals("Zbcdef", row(emu, 0, 6));
    }

    @Test
    @DisplayName("Text pushed past the right margin is lost, like ICH")
    void irm_dropsAtRightMargin() {
        TerminalEmulator emu = new TerminalEmulator(5, 3);
        send(emu, "abcd" + ESC + "[1;1H" + ESC + "[4h" + "X");
        assertEquals("Xabcd", row(emu, 0, 5));
        send(emu, ESC + "[1;1H" + "Y");
        assertEquals("YXabc", row(emu, 0, 5));
    }

    @Test
    @DisplayName("A wide character inserts two columns")
    void irm_wideCharacter() {
        TerminalEmulator emu = new TerminalEmulator(20, 5);
        send(emu, "abc" + ESC + "[1;2H" + ESC + "[4h" + "中");
        assertEquals('a', emu.getCell(0, 0, 0).character);
        assertEquals(0x4e2d, emu.getCell(0, 1, 0).character);
        assertEquals('b', emu.getCell(0, 3, 0).character);
        assertEquals('c', emu.getCell(0, 4, 0).character);
    }

    @Test
    @DisplayName("RIS turns insert mode off")
    void irm_clearedByRis() {
        TerminalEmulator emu = new TerminalEmulator(20, 5);
        send(emu, ESC + "[4h" + ESC + "c" + "abc" + ESC + "[1;1H" + "Z");
        assertEquals("Zbc", row(emu, 0, 3));
    }

    @Test
    @DisplayName("Other ANSI modes in the same sequence are logged, IRM still applied")
    void otherAnsiModes_logged() {
        TerminalEmulator emu = new TerminalEmulator(20, 5);
        List<String> reports = new ArrayList<>();
        emu.setUnhandledListener(reports::add);
        send(emu, ESC + "[20;4h");
        assertTrue(emu.dumpState().contains("\"insertMode\":true"));
        assertEquals(List.of("unhandled: CSI 20 h"), reports);
    }
}
