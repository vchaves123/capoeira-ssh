package br.com.capoeirassh.ssh.terminal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Answers to terminal queries. A program that asks and gets no answer waits for a timeout —
 * typically a visibly slow start — and then assumes the feature is missing.
 */
class TerminalEmulatorQueryRepliesTest {

    private static final String ESC = "\u001b";
    private static final String ST  = ESC + "\\";
    private static final String BEL = "\u0007";

    /** Sends {@code seq} and returns everything the emulator replied, concatenated. */
    private static String reply(TerminalEmulator emu, String seq) {
        List<byte[]> out = new ArrayList<>();
        emu.setDataListener(out::add);
        emu.processBytes(seq.getBytes(StandardCharsets.ISO_8859_1));
        emu.flushResponses();
        StringBuilder sb = new StringBuilder();
        for (byte[] b : out) sb.append(new String(b, StandardCharsets.ISO_8859_1));
        return sb.toString();
    }

    private static TerminalEmulator emu() {
        TerminalEmulator e = new TerminalEmulator(80, 24);
        e.setReportedColors(0xFFB000, 0x101010);
        e.setCellPixelSize(9, 18);
        return e;
    }

    // ── identification ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Secondary DA answers as a VT220")
    void secondaryDa() {
        assertEquals(ESC + "[>1;10;0c", reply(emu(), ESC + "[>c"));
        assertEquals(ESC + "[>1;10;0c", reply(emu(), ESC + "[>0c"));
    }

    @Test
    @DisplayName("XTVERSION answers with name and version in a DCS string")
    void xtversion() {
        String r = reply(emu(), ESC + "[>q");
        assertTrue(r.startsWith(ESC + "P>|Capoeira SSH ") && r.endsWith(ST), r);
        assertTrue(r.contains(br.com.capoeirassh.ssh.BuildInfo.VERSION), r);
    }

    @Test
    @DisplayName("Primary DA is unchanged")
    void primaryDaUnchanged() {
        assertEquals(ESC + "[?1;2c", reply(emu(), ESC + "[c"));
    }

    // ── colours ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("OSC 11 ? reports the background, mirroring a BEL terminator")
    void backgroundQuery_bel() {
        assertEquals(ESC + "]11;rgb:1010/1010/1010" + BEL, reply(emu(), ESC + "]11;?" + BEL));
    }

    @Test
    @DisplayName("OSC 10 ? reports the foreground, mirroring an ST terminator")
    void foregroundQuery_st() {
        assertEquals(ESC + "]10;rgb:ffff/b0b0/0000" + ST, reply(emu(), ESC + "]10;?" + ST));
    }

    @Test
    @DisplayName("OSC 4 palette queries are answered, several in one sequence")
    void paletteQueries() {
        assertEquals(ESC + "]4;1;rgb:8080/0000/0000" + BEL + ESC + "]4;15;rgb:ffff/ffff/ffff" + BEL,
                     reply(emu(), ESC + "]4;1;?;15;?" + BEL));
    }

    @Test
    @DisplayName("Setting colours is not supported: no reply, and it is reported")
    void colourSet_notAnswered() {
        TerminalEmulator e = emu();
        List<String> reports = new ArrayList<>();
        e.setUnhandledListener(reports::add);
        assertEquals("", reply(e, ESC + "]11;#000000" + BEL + ESC + "]4;1;#ff0000" + BEL + ESC + "]4;999;?" + BEL));
        assertEquals(List.of("unhandled: OSC 11 (10 bytes)", "unhandled: OSC 4 (11 bytes)"), reports);
    }

    // ── modes ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DECRQM reports set/reset for implemented private modes")
    void decrqmPrivate() {
        TerminalEmulator e = emu();
        assertEquals(ESC + "[?2004;2$y", reply(e, ESC + "[?2004$p"));
        reply(e, ESC + "[?2004h");
        assertEquals(ESC + "[?2004;1$y", reply(e, ESC + "[?2004$p"));
        assertEquals(ESC + "[?25;1$y", reply(e, ESC + "[?25$p"));
    }

    @Test
    @DisplayName("DECRQM: reset for an implemented mode that is off, not recognized for unimplemented ones")
    void decrqmUnknown() {
        assertEquals(ESC + "[?1000;2$y", reply(emu(), ESC + "[?1000$p"));
        assertEquals(ESC + "[?2026;0$y", reply(emu(), ESC + "[?2026$p"));
    }

    @Test
    @DisplayName("ANSI DECRQM covers insert mode")
    void decrqmAnsi() {
        TerminalEmulator e = emu();
        assertEquals(ESC + "[4;2$y", reply(e, ESC + "[4$p"));
        reply(e, ESC + "[4h");
        assertEquals(ESC + "[4;1$y", reply(e, ESC + "[4$p"));
    }

    // ── cursor and sizes ────────────────────────────────────────────────────

    @Test
    @DisplayName("Private cursor position report (DECXCPR)")
    void decxcpr() {
        assertEquals(ESC + "[?5;10R", reply(emu(), ESC + "[5;10H" + ESC + "[?6n"));
    }

    @Test
    @DisplayName("XTWINOPS 14/16/19 report pixel and character sizes")
    void windowSizeReports() {
        TerminalEmulator e = emu();
        assertEquals(ESC + "[4;432;720t", reply(e, ESC + "[14t"));   // 24*18, 80*9
        assertEquals(ESC + "[6;18;9t",    reply(e, ESC + "[16t"));
        assertEquals(ESC + "[9;24;80t",   reply(e, ESC + "[19t"));
    }

    @Test
    @DisplayName("Window-manipulating XTWINOPS operations are not honoured")
    void windowManipulation_ignored() {
        TerminalEmulator e = emu();
        assertEquals("", reply(e, ESC + "[8;50;200t" + ESC + "[3;0;0t"));
        assertEquals(80, e.getCols());
        assertEquals(24, e.getRows());
    }

    // ── title stack ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("XTWINOPS 22/23 save and restore the window title")
    void titleStack() {
        TerminalEmulator e = emu();
        List<String> titles = new ArrayList<>();
        e.setTitleListener(titles::add);
        reply(e, ESC + "]0;shell" + BEL);
        reply(e, ESC + "[22;0;0t");
        reply(e, ESC + "]0;vim" + BEL);
        reply(e, ESC + "[23;0;0t");
        assertEquals(List.of("shell", "vim", "shell"), titles);
    }

    @Test
    @DisplayName("Popping an empty title stack does nothing; the stack is bounded")
    void titleStack_boundsAndEmpty() {
        TerminalEmulator e = emu();
        List<String> titles = new ArrayList<>();
        e.setTitleListener(titles::add);
        reply(e, ESC + "[23t");
        assertEquals(List.of(), titles);
        for (int i = 0; i < 1000; i++) reply(e, ESC + "[22t");   // must not grow without bound
        assertDoesNotThrow(() -> reply(e, ESC + "[23t"));
    }
}
