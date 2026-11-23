package br.com.capoeirassh.ssh.terminal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every output capability the {@code xterm-256color} terminfo entry advertises — which is what
 * curses programs actually send, since that is the TERM Capoeira declares — must be handled by the
 * emulator, or be listed in {@link #KNOWN_GAPS} with the reason. This is the safety net that would
 * have caught REP ({@code rep}) long before nmon drew its frames one character long.
 *
 * <p>The expansions below were transcribed by hand (with sample parameters filled in), because
 * there is no {@code infocmp} on the Windows build machine. They should be re-checked against
 * {@code infocmp -1 xterm-256color} on a Linux box whenever the entry is suspected to have changed;
 * {@code docs/TERMINAL-SEQUENCES.md} carries the same caveat.
 *
 * <p>Input capabilities ({@code k*}, {@code kmous}) and the expected-reply patterns ({@code u8})
 * are not sequences the terminal receives, so they are not listed.
 */
class TerminfoConformanceTest {

    private static final String E = "\u001b";

    /** capability name -> its expansion, with sample parameters substituted. */
    private static final Map<String, String> CAPS = new LinkedHashMap<>();
    static {
        CAPS.put("bel",    "\u0007");
        CAPS.put("blink",  E + "[5m");
        CAPS.put("bold",   E + "[1m");
        CAPS.put("cbt",    E + "[Z");
        CAPS.put("civis",  E + "[?25l");
        CAPS.put("clear",  E + "[H" + E + "[2J");
        CAPS.put("cnorm",  E + "[?12l" + E + "[?25h");
        CAPS.put("cr",     "\r");
        CAPS.put("csr",    E + "[1;20r");
        CAPS.put("cub",    E + "[3D");
        CAPS.put("cub1",   "\b");
        CAPS.put("cud",    E + "[3B");
        CAPS.put("cud1",   "\n");
        CAPS.put("cuf",    E + "[3C");
        CAPS.put("cuf1",   E + "[C");
        CAPS.put("cup",    E + "[5;10H");
        CAPS.put("cuu",    E + "[3A");
        CAPS.put("cuu1",   E + "[A");
        CAPS.put("cvvis",  E + "[?12;25h");
        CAPS.put("dch",    E + "[3P");
        CAPS.put("dch1",   E + "[P");
        CAPS.put("dim",    E + "[2m");
        CAPS.put("dl",     E + "[3M");
        CAPS.put("dl1",    E + "[M");
        CAPS.put("dsl",    E + "]2;" + "\u0007");
        CAPS.put("E3",     E + "[3J");
        CAPS.put("ech",    E + "[3X");
        CAPS.put("ed",     E + "[J");
        CAPS.put("el",     E + "[K");
        CAPS.put("el1",    E + "[1K");
        CAPS.put("flash",  E + "[?5h" + E + "[?5l");          // padding "$<100/>" is stripped by tputs
        CAPS.put("home",   E + "[H");
        CAPS.put("hpa",    E + "[10G");
        CAPS.put("ht",     "\t");
        CAPS.put("hts",    E + "H");
        CAPS.put("ich",    E + "[3@");
        CAPS.put("il",     E + "[3L");
        CAPS.put("il1",    E + "[L");
        CAPS.put("ind",    "\n");
        CAPS.put("indn",   E + "[3S");
        CAPS.put("initc",  E + "]4;1;rgb:FF/00/00" + E + "\\");
        CAPS.put("invis",  E + "[8m");
        CAPS.put("is2",    E + "[!p" + E + "[?3;4l" + E + "[4l" + E + ">");
        CAPS.put("mc0",    E + "[i");
        CAPS.put("mc4",    E + "[4i");
        CAPS.put("mc5",    E + "[5i");
        CAPS.put("meml",   E + "l");
        CAPS.put("memu",   E + "m");
        CAPS.put("Ms",     E + "]52;c;aGk=" + "\u0007");
        CAPS.put("oc",     E + "]104" + "\u0007");
        CAPS.put("op",     E + "[39;49m");
        CAPS.put("rc",     E + "8");
        CAPS.put("rep",    "a" + E + "[4b");
        CAPS.put("rev",    E + "[7m");
        CAPS.put("ri",     E + "M");
        CAPS.put("rin",    E + "[3T");
        CAPS.put("ritm",   E + "[23m");
        CAPS.put("rmacs",  E + "(B");
        CAPS.put("rmam",   E + "[?7l");
        CAPS.put("rmcup",  E + "[?1049l" + E + "[23;0;0t");
        CAPS.put("rmir",   E + "[4l");
        CAPS.put("rmkx",   E + "[?1l" + E + ">");
        CAPS.put("rmm",    E + "[?1034l");
        CAPS.put("rmso",   E + "[27m");
        CAPS.put("rmul",   E + "[24m");
        CAPS.put("rmxx",   E + "[29m");
        CAPS.put("rs1",    E + "c");
        CAPS.put("rs2",    E + "[!p" + E + "[?3;4l" + E + "[4l" + E + ">");
        CAPS.put("sc",     E + "7");
        CAPS.put("Se",     E + "[2 q");
        CAPS.put("setab",  E + "[42m" + E + "[48;5;100m");
        CAPS.put("setaf",  E + "[31m" + E + "[38;5;100m");
        CAPS.put("setrgbb", E + "[48:2::10:20:30m");
        CAPS.put("setrgbf", E + "[38:2::10:20:30m");
        CAPS.put("Setulc", E + "[58:2::10:20:30m");
        CAPS.put("sgr",    E + "[0;1;4;5;7;8m" + E + "(0");
        CAPS.put("sgr0",   E + "(B" + E + "[m");
        CAPS.put("sitm",   E + "[3m");
        CAPS.put("smacs",  E + "(0");
        CAPS.put("smam",   E + "[?7h");
        CAPS.put("smcup",  E + "[?1049h" + E + "[22;0;0t");
        CAPS.put("smir",   E + "[4h");
        CAPS.put("smkx",   E + "[?1h" + E + "=");
        CAPS.put("smm",    E + "[?1034h");
        CAPS.put("smso",   E + "[7m");
        CAPS.put("smul",   E + "[4m");
        CAPS.put("Smulx",  E + "[4:3m");
        CAPS.put("smxx",   E + "[9m");
        CAPS.put("Ss",     E + "[3 q");
        CAPS.put("tbc",    E + "[3g");
        CAPS.put("TS",     E + "]2;title" + "\u0007");
        CAPS.put("u6",     E + "[6n");        // the query side of u6/u7
        CAPS.put("u9",     E + "[c");
        CAPS.put("vpa",    E + "[5d");
        CAPS.put("XM",     E + "[?1006;1000h");
        CAPS.put("BE",     E + "[?2004h");
        CAPS.put("BD",     E + "[?2004l");
    }

    /**
     * Capabilities the emulator receives but does not act on — each one a deliberate choice or a
     * known, documented gap. Implementing one makes {@link #knownGaps_areStillGaps} fail until
     * it is removed from here, so this list cannot silently go stale.
     */
    private static final Map<String, String> KNOWN_GAPS = Map.ofEntries(
        Map.entry("flash",  "DECSCNM (reverse screen, ?5) — visual bell not implemented"),
        Map.entry("is2",    "DECSTR soft reset (CSI ! p) and DECKPNM (ESC >) not implemented"),
        Map.entry("rs2",    "same as is2"),
        Map.entry("smkx",   "DECKPAM (ESC =): application keypad mode not implemented"),
        Map.entry("rmkx",   "DECKPNM (ESC >): application keypad mode not implemented"),
        Map.entry("smm",    "meta key mode (?1034) not implemented"),
        Map.entry("rmm",    "meta key mode (?1034) not implemented"),
        Map.entry("mc0",    "media copy / printer — deliberately unsupported"),
        Map.entry("mc4",    "media copy / printer — deliberately unsupported"),
        Map.entry("mc5",    "media copy / printer — deliberately unsupported"),
        Map.entry("meml",   "HP memory lock (ESC l) — not implemented"),
        Map.entry("memu",   "HP memory unlock (ESC m) — not implemented"),
        Map.entry("initc",  "setting palette colours (OSC 4) — not supported, only queries"),
        Map.entry("oc",     "resetting the palette (OSC 104) — nothing to reset, palette is fixed"),
        Map.entry("Ss",     "cursor style (DECSCUSR) — the cursor is always a block"),
        Map.entry("Se",     "cursor style (DECSCUSR) — the cursor is always a block"),
        Map.entry("Setulc", "underline colour (SGR 58) — consumed, not drawn"),
        Map.entry("Smulx",  "underline styles (SGR 4:2..4:5) — all drawn as a single underline")
    );

    private static List<String> feed(String seq) {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        List<String> reports = new ArrayList<>();
        emu.setUnhandledListener(reports::add);
        emu.setClipboardListener(text -> { });   // a UI is always attached in the real app
        emu.processBytes(seq.getBytes(StandardCharsets.ISO_8859_1));
        assertTrue(emu.dumpState().contains("\"parserState\":\"NORMAL\""),
            "parser did not return to NORMAL — part of the sequence would leak as text");
        return reports;
    }

    @Test
    @DisplayName("Every advertised capability is handled, or listed as a known gap")
    void everyCapabilityHandled() {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> cap : CAPS.entrySet()) {
            if (KNOWN_GAPS.containsKey(cap.getKey())) continue;
            List<String> reports = feed(cap.getValue());
            if (!reports.isEmpty()) failures.add(cap.getKey() + " -> " + reports);
        }
        assertEquals(List.of(), failures, "capabilities xterm-256color advertises but the emulator ignores");
    }

    @Test
    @DisplayName("Every known gap is still a gap (implementing one means removing it from the list)")
    void knownGaps_areStillGaps() {
        for (String name : KNOWN_GAPS.keySet()) {
            assertTrue(CAPS.containsKey(name), "known gap '" + name + "' is not a listed capability");
            assertFalse(feed(CAPS.get(name)).isEmpty(),
                "'" + name + "' is handled now — remove it from KNOWN_GAPS (and update docs/TERMINAL-SEQUENCES.md)");
        }
    }

    @Test
    @DisplayName("The known-gap list stays short — it is a to-do list, not a dumping ground")
    void knownGaps_bounded() {
        assertTrue(KNOWN_GAPS.size() <= 20, "too many known gaps: " + Set.copyOf(KNOWN_GAPS.keySet()));
    }
}
