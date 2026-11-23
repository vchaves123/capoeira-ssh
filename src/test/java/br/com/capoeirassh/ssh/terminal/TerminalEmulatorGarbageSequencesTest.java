package br.com.capoeirassh.ssh.terminal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sequences that used to leak part of themselves onto the screen as text: CSI with colon
 * sub-parameters, ESC with intermediates the parser didn't know ("ESC % G", "ESC SP F", the G2/G3
 * designators), and DEL. Each test checks that nothing is printed, that the cursor stays put,
 * and that whatever the sequence means is applied (or, when unsupported, reported).
 */
class TerminalEmulatorGarbageSequencesTest {

    private static final String ESC = "\u001b";

    private final List<String> reports = new ArrayList<>();

    private TerminalEmulator emulator() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        emu.setUnhandledListener(reports::add);
        return emu;
    }

    private static void send(TerminalEmulator emu, String seq) {
        emu.processBytes(seq.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** Writes "seq" then "X", and checks that X landed in column 0 — i.e. nothing leaked. */
    private static TerminalCell probe(TerminalEmulator emu, String seq) {
        send(emu, seq + "X");
        assertEquals(1, emu.getCursorCol(), "sequence leaked text onto the screen");
        assertEquals('X', emu.getCell(0, 0, 0).character);
        return emu.getCell(0, 0, 0);
    }

    // ── colon sub-parameters ────────────────────────────────────────────────

    @Test
    @DisplayName("ESC[4:3m underlines (curly drawn as single) and prints nothing")
    void curlyUnderline_underlines() {
        TerminalCell c = probe(emulator(), ESC + "[4:3m");
        assertTrue(c.underline);
        assertEquals(List.of("unhandled: SGR 4:3 (drawn as a single underline)"), reports);
    }

    @Test
    @DisplayName("ESC[4:0m switches underline off")
    void underlineStyleZero_switchesOff() {
        TerminalEmulator emu = emulator();
        send(emu, ESC + "[4m");
        assertFalse(probe(emu, ESC + "[4:0m").underline);
    }

    @Test
    @DisplayName("RGB colour: ITU form, its empty-colour-space variant and the shorthand all agree with the ; form")
    void rgbColon_formsAgreeWithSemicolonForm() {
        int expected = probe(emulator(), ESC + "[38;2;255;128;0m").fgColor;
        assertEquals(expected, probe(emulator(), ESC + "[38:2::255:128:0m").fgColor);
        assertEquals(expected, probe(emulator(), ESC + "[38:2:0:255:128:0m").fgColor);
        assertEquals(expected, probe(emulator(), ESC + "[38:2:255:128:0m").fgColor);
    }

    @Test
    @DisplayName("Indexed colour with colons sets fg and bg")
    void indexedColon_setsColours() {
        TerminalCell c = probe(emulator(), ESC + "[38:5:196;48:5:21m");
        assertEquals(196, c.fgColor);
        assertEquals(21, c.bgColor);
    }

    @Test
    @DisplayName("Colons mixed with plain parameters keep the plain ones working")
    void colonMixedWithPlainParams() {
        TerminalCell c = probe(emulator(), ESC + "[1;4:3;31m");
        assertTrue(c.bold);
        assertTrue(c.underline);
        assertEquals(1, c.fgColor);
    }

    @Test
    @DisplayName("A non-SGR CSI with sub-parameters is dropped whole and reported")
    void colonOutsideSgr_droppedAndReported() {
        TerminalEmulator emu = emulator();
        send(emu, ESC + "[5;5H");
        send(emu, ESC + "[1:2H");
        assertEquals(4, emu.getCursorRow(), "cursor must not move on a sequence we don't understand");
        assertEquals(4, emu.getCursorCol());
        assertEquals(List.of("unhandled: CSI 1:2 H"), reports);
    }

    // ── SGR 58 (underline colour) ───────────────────────────────────────────

    @Test
    @DisplayName("58;5;N is consumed whole: the 5 is not taken for blink")
    void underlineColourIndexed_doesNotBlink() {
        TerminalCell c = probe(emulator(), ESC + "[58;5;196m");
        assertFalse(c.blink, "58;5;196 turned blink on");
        assertEquals(List.of("unhandled: SGR 58"), reports);
    }

    @Test
    @DisplayName("58;2;R;G;B and 58:2::R:G:B are consumed whole")
    void underlineColourRgb_consumed() {
        TerminalCell c = probe(emulator(), ESC + "[58;2;5;7;1m");
        assertFalse(c.blink);
        assertFalse(c.reverse);
        assertFalse(probe(emulator(), ESC + "[58:2::5:7:1m").blink);
    }

    @Test
    @DisplayName("A malformed 38 stops there instead of reading its arguments as attributes")
    void malformedColour_doesNotLeakIntoAttributes() {
        TerminalCell c = probe(emulator(), ESC + "[38;5m");
        assertFalse(c.blink, "the 5 of an incomplete 38;5 turned blink on");
    }

    // ── ESC + intermediates ─────────────────────────────────────────────────

    @Test
    @DisplayName("ESC % G (UTF-8) and ESC SP F (7-bit) are consumed silently")
    void escPercentAndSpace_consumedSilently() {
        probe(emulator(), ESC + "%G" + ESC + " F");
        assertEquals(List.of(), reports);
    }

    @Test
    @DisplayName("ESC % @, ESC SP G and the G2/G3 designators are consumed and reported")
    void unsupportedDesignators_consumedAndReported() {
        probe(emulator(), ESC + "%@" + ESC + " G" + ESC + "*B" + ESC + "+0" + ESC + "-A" + ESC + ".A" + ESC + "/A");
        assertEquals(List.of("unhandled: ESC % @", "unhandled: ESC SP G", "unhandled: ESC * B",
                             "unhandled: ESC + 0", "unhandled: ESC - A", "unhandled: ESC . A",
                             "unhandled: ESC / A"), reports);
    }

    @Test
    @DisplayName("A two-byte designator (ESC ( % 5) is consumed whole and leaves G0 as ASCII")
    void twoByteDesignator_consumed() {
        TerminalEmulator emu = emulator();
        send(emu, ESC + "(0");                           // line drawing on
        send(emu, ESC + "(%5" + "q");
        assertEquals('q', emu.getCell(0, 0, 0).character, "G0 must be back to ASCII");
        assertEquals(1, emu.getCursorCol());
        assertEquals(List.of("unhandled: ESC (% 5"), reports);
    }

    @Test
    @DisplayName("Charset designation and DECALN still work through the new path")
    void existingEscIntermediates_unchanged() {
        TerminalEmulator emu = emulator();
        send(emu, ESC + "(0q" + ESC + "(Bq");
        assertEquals('─', emu.getCell(0, 0, 0).character);
        assertEquals('q', emu.getCell(0, 1, 0).character);
        send(emu, ESC + ")0" + "\u000e" + "q" + "\u000f");   // G1 line drawing via SO/SI
        assertEquals('─', emu.getCell(0, 2, 0).character);
        send(emu, ESC + "#8");
        assertEquals('E', emu.getCell(10, 40, 0).character);
        assertEquals(List.of(), reports);
    }

    @Test
    @DisplayName("ESC # 3 (double height) is consumed and reported")
    void doubleHeight_consumedAndReported() {
        probe(emulator(), ESC + "#3");
        assertEquals(List.of("unhandled: ESC # 3"), reports);
    }

    // ── DEL ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DEL in text is ignored: nothing written, cursor unmoved")
    void delInText_ignored() {
        TerminalEmulator emu = emulator();
        send(emu, "a\u007fb");
        assertEquals('a', emu.getCell(0, 0, 0).character);
        assertEquals('b', emu.getCell(0, 1, 0).character);
        assertEquals(2, emu.getCursorCol());
    }

    @Test
    @DisplayName("DEL inside a CSI does not break it")
    void delInsideCsi_sequenceStillApplies() {
        TerminalEmulator emu = emulator();
        send(emu, ESC + "[1\u007f0;2\u007f0H");
        assertEquals(9, emu.getCursorRow());
        assertEquals(19, emu.getCursorCol());
    }
}
