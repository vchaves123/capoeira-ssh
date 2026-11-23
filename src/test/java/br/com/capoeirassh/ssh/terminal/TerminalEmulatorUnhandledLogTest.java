package br.com.capoeirassh.ssh.terminal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The unhandled-sequence report: every escape sequence the emulator receives but does nothing with
 * is announced once, so missing features show up in app.log instead of as a silent misrender (as
 * REP did, until nmon drew its frames one character long). The other half matters as much:
 * sequences that ARE handled must never be reported, or the log turns into noise nobody reads.
 */
class TerminalEmulatorUnhandledLogTest {

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

    @Test
    @DisplayName("An unknown CSI sequence is reported once, however often it arrives")
    void unknownCsi_reportedOnce() {
        TerminalEmulator emu = emulator();
        send(emu, ESC + "[5z" + ESC + "[5z" + ESC + "[5z");
        assertEquals(List.of("unhandled: CSI 5 z"), reports);
    }

    @Test
    @DisplayName("Private modes that are accepted but not implemented (mouse, focus) are reported")
    void unimplementedPrivateModes_reported() {
        TerminalEmulator emu = emulator();
        send(emu, ESC + "[?1000h" + ESC + "[?1004h");
        assertEquals(List.of("unhandled: CSI ? 1000 h", "unhandled: CSI ? 1004 h"), reports);
    }

    @Test
    @DisplayName("Queries left unanswered are reported (secondary DA, private DSR)")
    void unansweredQueries_reported() {
        TerminalEmulator emu = emulator();
        send(emu, ESC + "[?u" + ESC + "[7n" + ESC + "[8;24;80t" + ESC + "[14t");   // no cell size set
        assertEquals(List.of("unhandled: CSI ? u", "unhandled: CSI 7 n",
                             "unhandled: CSI 8;24;80 t", "unhandled: CSI 14 t"), reports);
    }

    @Test
    @DisplayName("Sequences with intermediate bytes are reported with them, a space spelled SP")
    void intermediates_reported() {
        TerminalEmulator emu = emulator();
        send(emu, ESC + "[1 q" + ESC + "[!p");   // DECSCUSR, DECSTR
        assertEquals(List.of("unhandled: CSI 1 SP q", "unhandled: CSI ! p"), reports);
    }

    @Test
    @DisplayName("DECSET with several modes reports just the modes it does not implement")
    void multiParameterDecset_reportsOnlyUnimplementedModes() {
        TerminalEmulator emu = emulator();
        send(emu, ESC + "[?25;1004h");
        assertEquals(List.of("unhandled: CSI ? 1004 h"), reports);
    }

    @Test
    @DisplayName("Unsupported SGR attributes are reported one by one")
    void unsupportedSgr_reported() {
        TerminalEmulator emu = emulator();
        send(emu, ESC + "[21;53m");   // double underline, overline
        assertEquals(List.of("unhandled: SGR 21", "unhandled: SGR 53"), reports);
    }

    @Test
    @DisplayName("An ignored OSC is reported by number and length, never by its content")
    void ignoredOsc_reportsNumberAndLengthOnly() {
        TerminalEmulator emu = emulator();
        send(emu, ESC + "]52;c;SECRETDATA\u0007");
        assertEquals(List.of("unhandled: OSC 52 (15 bytes)"), reports);
        assertTrue(reports.stream().noneMatch(r -> r.contains("SECRET")), "payload must never reach the log");
    }

    @Test
    @DisplayName("DCS/APC strings are reported by kind and length")
    void dcsAndApc_reported() {
        TerminalEmulator emu = emulator();
        send(emu, ESC + "P$qm" + ESC + "\\" + ESC + "_Gi=1" + ESC + "\\");
        assertEquals(List.of("unhandled: DCS (3 bytes)", "unhandled: APC (4 bytes)"), reports);
    }

    @Test
    @DisplayName("Unhandled ESC, ESC # and charset designations are reported; a stray ST is not")
    void escFamilies_reported() {
        TerminalEmulator emu = emulator();
        send(emu, ESC + "=" + ESC + "#3" + ESC + "(A" + ESC + "\\");
        assertEquals(List.of("unhandled: ESC =", "unhandled: ESC # 3", "unhandled: ESC ( A"), reports);
    }

    @Test
    @DisplayName("Ignored C1 controls are reported")
    void c1_reported() {
        TerminalEmulator emu = emulator();
        send(emu, "\u0088");                      // HTS in its 8-bit form
        assertEquals(List.of("unhandled: C1 0x88"), reports);
    }

    @Test
    @DisplayName("DEL inside a sequence is skipped, not taken as its final byte")
    void delInsideCsi_isSkipped() {
        TerminalEmulator emu = emulator();
        send(emu, ESC + "[5\u007fz");
        assertEquals(List.of("unhandled: CSI 5 z"), reports);
    }

    @Test
    @DisplayName("The report stops after 200 distinct sequences, with one line saying so")
    void report_isCapped() {
        TerminalEmulator emu = emulator();
        for (int i = 1; i <= 250; i++) send(emu, ESC + "[" + i + "z");
        assertEquals(201, reports.size());
        assertTrue(reports.get(200).contains("limit (200) reached"), "got: " + reports.get(200));
    }

    @Test
    @DisplayName("Sequences the emulator handles are never reported")
    void handledSequences_neverReported() {
        TerminalEmulator emu = emulator();
        String[] handled = {
            "[H", "[2J", "[3J", "[K", "[1K", "[1;31m", "[0m", "[m", "[38;5;196m", "[48;2;1;2;3m",
            "[1;4;5;7m", "[22;24;25;27;39;49m", "[2;3;8;9m", "[23;28;29m", "[4h", "[4l", "[I", "[2Z", "[>c", "[>q", "[?6n", "[?2004$p", "[4$p", "[19t",
            "[22;0;0t", "[23;0;0t", "]10;?\u0007", "]11;?\u0007", "]4;1;?\u0007", "[92;104m", "[?25l", "[?25h", "[?1049h", "[?1049l",
            "[?1h", "[?1l", "[?7l", "[?7h", "[?2004h", "[?2004l", "[?4l", "[?12h", "[?12l",
            "[5;10r", "[r", "[L", "[M", "[P", "[X", "[@", "[S", "[T", "[d", "[G", "[A", "[B",
            "[C", "[D", "[E", "[F", "[3;4f", "[s", "[u", "[3g", "[g", "[6n", "[5n", "[c", "[18t",
            "7", "8", "M", "D", "E", "H", "#8", "(0", "(B", ")0", ")B", "]0;title\u0007",
            "]2;title\u0007", "\\",
        };
        send(emu, "x");
        for (String s : handled) send(emu, ESC + s);
        send(emu, "y" + ESC + "[3b");             // REP
        send(emu, ESC + "c");                     // RIS last: it resets everything
        assertEquals(List.of(), reports);
    }

    @Test
    @DisplayName("Without a listener nothing is collected and nothing breaks")
    void noListener_isFine() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        assertDoesNotThrow(() -> send(emu, ESC + "[5z" + ESC + "]52;c;x\u0007" + ESC + "[2m"));
    }
}
