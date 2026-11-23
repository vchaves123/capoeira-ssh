package br.com.capoeirassh.ssh.terminal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Resizing the window while a full-screen program owns the alternate screen. Reproduces a real
 * capture: nmon started on a 32-row window, the window was then enlarged to 51 rows while nmon was
 * running, nmon redrew by leaving and re-entering the alternate screen, and after it quit the
 * shell prompt was stuck on row 31 with no scrollback — the exit had restored the scroll region
 * saved at the old size, and the cursor was never put back under the command line.
 */
class TerminalEmulatorAltScreenResizeTest {

    private static final String ESC = "\u001b";

    private static void send(TerminalEmulator emu, String seq) {
        emu.processBytes(seq.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static int field(TerminalEmulator emu, String name) throws Exception {
        Field f = TerminalEmulator.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(emu);
    }

    @Test
    @DisplayName("Growing the window while on the alternate screen: the exit restores a full-height scroll region")
    void resizeOnAltScreen_exitRestoresFullRegion() throws Exception {
        TerminalEmulator emu = new TerminalEmulator(93, 32);
        send(emu, "[root]# nmon\r\n");
        send(emu, ESC + "[?1049h" + ESC + "[1;32r");   // nmon: enter alt screen, region = 32 rows

        emu.resize(210, 51);                           // window enlarged while nmon runs
        send(emu, ESC + "[?1049l");                    // nmon exits

        assertEquals(0,  field(emu, "scrollTop"));
        assertEquals(50, field(emu, "scrollBottom"),
            "the region must span the new, taller screen — not the 32-row one saved on entry");
    }

    @Test
    @DisplayName("After that exit, line feeds scroll the whole screen and feed the scrollback")
    void resizeOnAltScreen_exitLeavesPromptFreeToReachTheBottom() {
        TerminalEmulator emu = new TerminalEmulator(93, 32);
        send(emu, ESC + "[?1049h" + ESC + "[1;32r");
        emu.resize(210, 51);
        send(emu, ESC + "[?1049l");

        for (int i = 0; i < 80; i++) send(emu, "\r\n");
        assertEquals(50, emu.getCursorRow(), "cursor must be able to reach the last row of the 51-row screen");
        assertFalse(emu.dumpState().contains("\"scrollbackLines\":0"),
            "lines scrolling off a full-screen region must land in the scrollback");
    }

    @Test
    @DisplayName("The saved cursor is clamped when the window shrinks while on the alternate screen")
    void resizeOnAltScreen_shrinkClampsSavedCursor() {
        TerminalEmulator emu = new TerminalEmulator(80, 40);
        send(emu, ESC + "[30;5H");                     // cursor at row 29
        send(emu, ESC + "[?1049h");
        emu.resize(80, 10);
        send(emu, ESC + "[?1049l");
        assertTrue(emu.getCursorRow() <= 9, "restored cursor must lie inside the smaller screen");
    }

    @Test
    @DisplayName("nmon's resize redraw (real bytes): prompt returns under the command and the region stays full")
    void nmonResizeRedraw_realSequence() throws Exception {
        TerminalEmulator emu = new TerminalEmulator(93, 32);
        send(emu, "[root]# nmon\r\n");                 // cursor now at row 1, col 0

        send(emu, ESC + "[?1049h" + ESC + "[22;0;0t" + ESC + "[1;32r");   // enter, 32-row region
        send(emu, ESC + "[H" + ESC + "[2J");

        emu.resize(210, 51);                           // user enlarges the window; nmon gets SIGWINCH

        // ncurses tears the screen down: bottom line, leave alt screen, restore...
        send(emu, ESC + "[39;49m" + ESC + "[32d" + ESC + "[K" + ESC + "[32;1H"
                + ESC + "[?1049l" + ESC + "[23;0;0t" + "\r" + ESC + "[?1l" + ESC + ">");
        // ...and again, now already on the normal screen (the second ?1049l)
        send(emu, ESC + "[39;49m" + ESC + "[K" + ESC + "[32;1H"
                + ESC + "[?1049l" + ESC + "[23;0;0t");
        // then redraws at the new size
        send(emu, ESC + "[?1049h" + ESC + "[22;0;0t" + ESC + "[1;51r");
        send(emu, ESC + "[H" + ESC + "[2J");

        // user presses q: nmon moves to the last line and exits
        send(emu, ESC + "[39;49m" + ESC + "[51d" + ESC + "[K" + ESC + "[51;1H"
                + ESC + "[?1049l" + ESC + "[23;0;0t" + "\r" + ESC + "[?1l" + ESC + ">");

        assertEquals(0,  field(emu, "scrollTop"));
        assertEquals(50, field(emu, "scrollBottom"));
        assertEquals(1,  emu.getCursorRow(), "prompt must come back right under the nmon command line");
        assertEquals(0,  emu.getCursorCol());
    }

    @Test
    @DisplayName("A second DECRST 1049 on the normal screen restores the saved cursor")
    void secondExit1049_restoresSavedCursor() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[5;7H");
        send(emu, ESC + "[?1049h");
        send(emu, ESC + "[?1049l");
        send(emu, ESC + "[20;1H");                     // cursor wanders on the normal screen
        send(emu, ESC + "[?1049l");                    // already out: xterm still does a DECRC
        assertEquals(4, emu.getCursorRow());
        assertEquals(6, emu.getCursorCol());
    }

    @Test
    @DisplayName("DECRST 1049 on a fresh terminal (nothing ever saved) leaves the cursor alone")
    void exit1049_withoutAnyPriorEntry_doesNotMoveCursor() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[10;10H");
        send(emu, ESC + "[?1049l");
        assertEquals(9, emu.getCursorRow());
        assertEquals(9, emu.getCursorCol());
    }

    @Test
    @DisplayName("DECRST 47/1047 on the normal screen never moves the cursor")
    void exit47And1047_onNormalScreen_doNotMoveCursor() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[5;5H" + ESC + "[?1049h" + ESC + "[?1049l");
        send(emu, ESC + "[12;3H");
        send(emu, ESC + "[?47l" + ESC + "[?1047l");
        assertEquals(11, emu.getCursorRow());
        assertEquals(2,  emu.getCursorCol());
    }
}
