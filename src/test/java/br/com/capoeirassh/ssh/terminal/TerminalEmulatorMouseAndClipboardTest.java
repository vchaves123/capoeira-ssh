package br.com.capoeirassh.ssh.terminal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static br.com.capoeirassh.ssh.terminal.TerminalEmulator.*;
import static org.junit.jupiter.api.Assertions.*;

/** Mouse reporting (DECSET 9/1000/1002/1003 + 1005/1006/1015), focus and alternate-scroll
 *  modes, and OSC 52 clipboard writes. */
class TerminalEmulatorMouseAndClipboardTest {

    private static final String ESC = "\u001b";

    private static void send(TerminalEmulator emu, String seq) {
        emu.processBytes(seq.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String ev(TerminalEmulator emu, int button, boolean release, boolean motion, int col, int row, int mods) {
        byte[] b = emu.encodeMouseEvent(button, release, motion, col, row, mods);
        return b == null ? null : new String(b, StandardCharsets.ISO_8859_1);
    }

    // ── which events ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Nothing is reported until the program asks for it")
    void offByDefault() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        assertFalse(emu.isMouseReporting());
        assertNull(ev(emu, MOUSE_LEFT, false, false, 0, 0, 0));
    }

    @Test
    @DisplayName("1000 + SGR: press and release, release keeps its button; motion is not reported")
    void normalTracking_sgr() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[?1000;1006h");
        assertTrue(emu.isMouseReporting());
        assertEquals(ESC + "[<0;5;10M", ev(emu, MOUSE_LEFT, false, false, 4, 9, 0));
        assertEquals(ESC + "[<2;5;10m", ev(emu, MOUSE_RIGHT, true, false, 4, 9, 0));
        assertNull(ev(emu, MOUSE_LEFT, false, true, 5, 9, 0));
    }

    @Test
    @DisplayName("1002 reports drags but not bare motion; 1003 reports both")
    void buttonAndAnyEventTracking() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[?1002;1006h");
        assertEquals(ESC + "[<32;6;10M", ev(emu, MOUSE_LEFT, false, true, 5, 9, 0));
        assertNull(ev(emu, MOUSE_NONE, false, true, 5, 9, 0));
        send(emu, ESC + "[?1003h");
        assertEquals(ESC + "[<35;6;10M", ev(emu, MOUSE_NONE, false, true, 5, 9, 0));
    }

    @Test
    @DisplayName("X10 mode (9) reports presses only, without modifiers")
    void x10() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[?9h");
        assertEquals(ESC + "[M" + (char) 32 + (char) 33 + (char) 33, ev(emu, MOUSE_LEFT, false, false, 0, 0, MOUSE_MOD_CTRL));
        assertNull(ev(emu, MOUSE_LEFT, true, false, 0, 0, 0));
        assertNull(ev(emu, MOUSE_WHEEL_UP, false, false, 0, 0, 0));
    }

    @Test
    @DisplayName("Wheel events are buttons 64/65 and have no release; modifiers add 4/8/16")
    void wheelAndModifiers() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[?1000;1006h");
        assertEquals(ESC + "[<64;1;1M", ev(emu, MOUSE_WHEEL_UP, false, false, 0, 0, 0));
        assertEquals(ESC + "[<65;1;1M", ev(emu, MOUSE_WHEEL_DOWN, false, false, 0, 0, 0));
        assertNull(ev(emu, MOUSE_WHEEL_UP, true, false, 0, 0, 0));
        assertEquals(ESC + "[<16;1;1M", ev(emu, MOUSE_LEFT, false, false, 0, 0, MOUSE_MOD_CTRL));
        assertEquals(ESC + "[<8;1;1M",  ev(emu, MOUSE_LEFT, false, false, 0, 0, MOUSE_MOD_META));
    }

    // ── encodings ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Legacy encoding: three bytes offset by 32; release is button 3")
    void legacyEncoding() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[?1000h");
        assertEquals(ESC + "[M" + (char) 32 + (char) 37 + (char) 42, ev(emu, MOUSE_LEFT, false, false, 4, 9, 0));
        assertEquals(ESC + "[M" + (char) 35 + (char) 37 + (char) 42, ev(emu, MOUSE_LEFT, true, false, 4, 9, 0));
    }

    @Test
    @DisplayName("Legacy encoding can't carry columns past 223; SGR can")
    void legacyOutOfRange() {
        TerminalEmulator emu = new TerminalEmulator(300, 24);
        send(emu, ESC + "[?1000h");
        assertNull(ev(emu, MOUSE_LEFT, false, false, 250, 0, 0));
        send(emu, ESC + "[?1006h");
        assertEquals(ESC + "[<0;251;1M", ev(emu, MOUSE_LEFT, false, false, 250, 0, 0));
    }

    @Test
    @DisplayName("urxvt encoding (1015): decimal, button offset by 32")
    void urxvtEncoding() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[?1000;1015h");
        assertEquals(ESC + "[32;5;10M", ev(emu, MOUSE_LEFT, false, false, 4, 9, 0));
    }

    // ── mode bookkeeping ────────────────────────────────────────────────────

    @Test
    @DisplayName("Resetting the active mode turns reporting off; resetting another one doesn't")
    void modeReset() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[?1002h" + ESC + "[?1000l");
        assertTrue(emu.isMouseReporting());
        send(emu, ESC + "[?1002l");
        assertFalse(emu.isMouseReporting());
    }

    @Test
    @DisplayName("RIS turns mouse, focus and alternate scroll off")
    void risResets() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[?1003;1004;1007h" + ESC + "c");
        assertFalse(emu.isMouseReporting());
        assertFalse(emu.isFocusReporting());
        send(emu, ESC + "[?1049h");
        assertFalse(emu.isAltScrollActive());
    }

    @Test
    @DisplayName("Focus reporting follows DECSET 1004")
    void focusMode() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[?1004h");
        assertTrue(emu.isFocusReporting());
        send(emu, ESC + "[?1004l");
        assertFalse(emu.isFocusReporting());
    }

    @Test
    @DisplayName("Alternate scroll applies only on the alternate screen and with mouse reporting off")
    void altScroll() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[?1007h");
        assertFalse(emu.isAltScrollActive(), "not on the normal screen");
        send(emu, ESC + "[?1049h");
        assertTrue(emu.isAltScrollActive());
        send(emu, ESC + "[?1000h");
        assertFalse(emu.isAltScrollActive(), "mouse reporting takes the wheel");
    }

    // ── OSC 52 ──────────────────────────────────────────────────────────────

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("OSC 52 writes decoded UTF-8 text to the clipboard")
    void osc52Write() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        List<String> clip = new ArrayList<>();
        emu.setClipboardListener(clip::add);
        send(emu, ESC + "]52;c;" + b64("olá, mundo") + "\u0007");
        assertEquals(List.of("olá, mundo"), clip);
    }

    @Test
    @DisplayName("OSC 52 read query is never answered (and is reported)")
    void osc52QueryRefused() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        List<String> clip = new ArrayList<>();
        List<String> reports = new ArrayList<>();
        List<byte[]> replies = new ArrayList<>();
        emu.setClipboardListener(clip::add);
        emu.setUnhandledListener(reports::add);
        emu.setDataListener(replies::add);
        send(emu, ESC + "]52;c;?\u0007");
        emu.flushResponses();
        assertTrue(clip.isEmpty());
        assertTrue(replies.isEmpty(), "the local clipboard must never be sent to the remote side");
        assertEquals(List.of("unhandled: OSC 52 (6 bytes)"), reports);
    }

    @Test
    @DisplayName("Invalid base64 and a payload cut short by the size cap are not copied")
    void osc52BadPayloads() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        List<String> clip = new ArrayList<>();
        emu.setClipboardListener(clip::add);
        send(emu, ESC + "]52;c;@@not-base64@@\u0007");
        send(emu, ESC + "]52;c;" + "QUFB".repeat(70_000) + "\u0007");   // 280 000 chars > cap
        assertTrue(clip.isEmpty());
        send(emu, ESC + "]52;c;" + b64("ok") + "\u0007");   // a good one afterwards still works
        assertEquals(List.of("ok"), clip);
    }

    @Test
    @DisplayName("DECRQM reports the mouse modes")
    void decrqmMouse() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        List<byte[]> out = new ArrayList<>();
        emu.setDataListener(out::add);
        send(emu, ESC + "[?1006h" + ESC + "[?1006$p");
        emu.flushResponses();
        assertEquals(ESC + "[?1006;1$y", new String(out.get(0), StandardCharsets.US_ASCII));
    }
}
