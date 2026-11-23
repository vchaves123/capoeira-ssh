package br.com.capoeirassh.ssh.terminal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** SGR 2 (faint), 3 (italic), 8 (concealed) and 9 (strike-through), and their resets. */
class TerminalEmulatorSgrStylesTest {

    private static final String ESC = "\u001b";

    private static TerminalCell write(TerminalEmulator emu, String seq) {
        emu.processBytes((seq + "X").getBytes(StandardCharsets.ISO_8859_1));
        return emu.getCell(emu.getCursorRow(), emu.getCursorCol() - 1, 0);
    }

    @Test
    @DisplayName("Each attribute is set on the cells written after it")
    void eachAttributeSet() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        assertTrue(write(emu, ESC + "[2m").dim);
        assertTrue(write(emu, ESC + "[0;3m").italic);
        assertTrue(write(emu, ESC + "[0;8m").invisible);
        assertTrue(write(emu, ESC + "[0;9m").strike);
    }

    @Test
    @DisplayName("Their own resets clear them: 22 (dim and bold), 23, 28, 29")
    void resets() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        TerminalCell c = write(emu, ESC + "[1;2;3;8;9m" + ESC + "[22;23;28;29m");
        assertFalse(c.bold);
        assertFalse(c.dim);
        assertFalse(c.italic);
        assertFalse(c.invisible);
        assertFalse(c.strike);
    }

    @Test
    @DisplayName("22 does not touch italic or strike; 23 does not touch dim")
    void resetsAreIndependent() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        TerminalCell c = write(emu, ESC + "[2;3;9m" + ESC + "[22m");
        assertTrue(c.italic);
        assertTrue(c.strike);
        c = write(emu, ESC + "[2m" + ESC + "[23m");
        assertTrue(c.dim);
    }

    @Test
    @DisplayName("SGR 0 clears all four")
    void sgr0ClearsAll() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        TerminalCell c = write(emu, ESC + "[2;3;8;9m" + ESC + "[0m");
        assertFalse(c.dim || c.italic || c.invisible || c.strike);
    }

    @Test
    @DisplayName("Erase leaves no style behind; copyFrom carries them")
    void eraseAndCopy() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        write(emu, ESC + "[2;3;8;9m");
        emu.processBytes((ESC + "[0m" + ESC + "[1G" + ESC + "[K").getBytes(StandardCharsets.ISO_8859_1));
        TerminalCell erased = emu.getCell(0, 0, 0);
        assertFalse(erased.dim || erased.italic || erased.invisible || erased.strike);

        TerminalCell src = new TerminalCell();
        src.dim = src.italic = src.invisible = src.strike = true;
        TerminalCell dst = new TerminalCell(src);
        assertTrue(dst.dim && dst.italic && dst.invisible && dst.strike);
    }

    @Test
    @DisplayName("The state dump shows the new attributes")
    void dumpStateShowsThem() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        write(emu, ESC + "[2;3;8;9m");
        String s = emu.dumpState();
        assertTrue(s.contains("\"dim\":true") && s.contains("\"italic\":true")
                && s.contains("\"invisible\":true") && s.contains("\"strike\":true"), s);
    }
}
