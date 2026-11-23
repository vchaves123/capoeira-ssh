package br.com.capoeirassh.ssh.terminal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DECSET/DECRST with several modes in one sequence. Only the first mode used to be applied, so
 * terminfo strings that combine modes silently lost the rest — cvvis "ESC[?12;25h" left a hidden
 * cursor hidden, rs2 "ESC[?3;4l" never touched mode 4, "ESC[?1049;2004h" lost bracketed paste.
 */
class TerminalEmulatorDecsetMultiParamTest {

    private static final String ESC = "\u001b";

    private static void send(TerminalEmulator emu, String seq) {
        emu.processBytes(seq.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    @DisplayName("cvvis (?12;25h) shows a hidden cursor")
    void cvvis_showsCursor() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[?25l");
        send(emu, ESC + "[?12;25h");
        assertTrue(emu.isCursorVisible());
    }

    @Test
    @DisplayName("Every mode in the list is applied, set and reset")
    void allModesApplied() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[?1;2004h");
        assertTrue(emu.isAppCursorKeys());
        assertTrue(emu.isBracketedPaste());
        send(emu, ESC + "[?2004;1l");
        assertFalse(emu.isAppCursorKeys());
        assertFalse(emu.isBracketedPaste());
    }

    @Test
    @DisplayName("Alternate screen plus another mode in one sequence")
    void altScreenCombinedWithOtherMode() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[?1049;2004h");
        assertTrue(emu.isAltBufferActive());
        assertTrue(emu.isBracketedPaste());
        send(emu, ESC + "[?1049;2004l");
        assertFalse(emu.isAltBufferActive());
        assertFalse(emu.isBracketedPaste());
    }

    @Test
    @DisplayName("An unimplemented mode in the middle does not stop the ones after it")
    void unknownModeInTheMiddle_doesNotStopTheRest() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[?25l");
        send(emu, ESC + "[?9999;25h");
        assertTrue(emu.isCursorVisible());
    }

    @Test
    @DisplayName("DECSET with an empty parameter list does not throw")
    void emptyParams_doNotThrow() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        assertDoesNotThrow(() -> send(emu, ESC + "[?h" + ESC + "[?l"));
    }
}
