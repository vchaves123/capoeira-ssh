package br.com.capoeirassh.ssh.terminal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link TerminalEmulator}'s escape-sequence parser against malformed/adversarial
 * input: missing, zeroed, negative-looking and absurdly large CSI parameters; sequences
 * truncated mid-stream; an OSC string with no terminator; resize with the cursor left outside
 * the new bounds; and an invalid DECSTBM scroll region. No production code is modified — private
 * scroll-region fields are read via reflection only where there is no public accessor.
 */
class TerminalEmulatorTest {

    private static final String ESC = "";

    private static void send(TerminalEmulator emu, String seq) {
        emu.processBytes(seq.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static int scrollTop(TerminalEmulator emu) throws Exception {
        Field f = TerminalEmulator.class.getDeclaredField("scrollTop");
        f.setAccessible(true);
        return f.getInt(emu);
    }

    private static int scrollBottom(TerminalEmulator emu) throws Exception {
        Field f = TerminalEmulator.class.getDeclaredField("scrollBottom");
        f.setAccessible(true);
        return f.getInt(emu);
    }

    // -----------------------------------------------------------------------
    // CSI: missing / zeroed parameters
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("CUP with all parameters omitted defaults to row 1, col 1 (home)")
    void csi_missingParams_cupDefaultsToHome() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[10;10H"); // move away from home first
        send(emu, ESC + "[H");
        assertEquals(0, emu.getCursorRow());
        assertEquals(0, emu.getCursorCol());
    }

    @Test
    @DisplayName("CUP with explicit zero parameters behaves the same as omitted (both mean row/col 1)")
    void csi_zeroParams_treatedAsOne() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[10;10H");
        send(emu, ESC + "[0;0H");
        assertEquals(0, emu.getCursorRow());
        assertEquals(0, emu.getCursorCol());
    }

    @Test
    @DisplayName("CUF with an explicit zero count still moves one cell (zero clamped to the implicit minimum of 1)")
    void csi_zeroCountCursorMove_stillMovesOne() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[10;10H"); // row 9, col 9 (0-based)
        send(emu, ESC + "[0C"); // CUF with explicit 0
        assertEquals(10, emu.getCursorCol(), "0 must be treated as 1, not as \"don't move\"");
    }

    // -----------------------------------------------------------------------
    // CSI: negative-looking parameters
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("A '-' inside a CSI sequence is an intermediate byte, not a sign: the whole sequence is swallowed and ignored, cursor unaffected")
    void csi_negativeLookingParam_ignoredAsIntermediate() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[6;6H"); // row 5, col 5 (0-based)
        assertDoesNotThrow(() -> send(emu, ESC + "[-5H"));
        assertEquals(5, emu.getCursorRow(), "malformed sequence must not move the cursor");
        assertEquals(5, emu.getCursorCol());
        // Parser must still be usable afterwards — not stuck in a broken state.
        send(emu, ESC + "[H");
        assertEquals(0, emu.getCursorRow());
        assertEquals(0, emu.getCursorCol());
    }

    // -----------------------------------------------------------------------
    // CSI: absurdly large parameters
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("An absurdly large CUP parameter is clamped to the screen bounds instead of throwing")
    void csi_absurdlyLargeParam_clampedToScreenBounds() {
        TerminalEmulator emu = new TerminalEmulator(10, 5);
        assertDoesNotThrow(() -> send(emu, ESC + "[99999999999;99999999999H"));
        assertEquals(4, emu.getCursorRow(), "must clamp to rows-1");
        assertEquals(9, emu.getCursorCol(), "must clamp to cols-1");
    }

    @Test
    @DisplayName("A CUF with a param far beyond int range does not throw and clamps to the right margin")
    void csi_absurdlyLargeRelativeMove_clampedWithoutOverflow() {
        TerminalEmulator emu = new TerminalEmulator(10, 5);
        assertDoesNotThrow(() -> send(emu, ESC + "[H"));
        assertDoesNotThrow(() -> send(emu, ESC + "[999999999999999999999C"));
        assertEquals(9, emu.getCursorCol());
    }

    @Test
    @DisplayName("Insert-lines with a huge repeat count (CSI L) completes quickly instead of hanging the parser thread")
    void csi_hugeRepeatCount_insertLines_doesNotHang() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        long start = System.nanoTime();
        assertDoesNotThrow(() -> send(emu, ESC + "[2000000000L"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 5_000, "expected the internal clamp to keep this fast, took " + elapsedMs + "ms");
    }

    @Test
    @DisplayName("A CSI sequence with far more than MAX_CSI_PARAMS ';'-separated parameters is capped, not grown unbounded")
    void csi_excessiveParamCount_cappedWithoutHanging() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        StringBuilder sb = new StringBuilder(ESC + "[");
        for (int i = 0; i < 5000; i++) sb.append("1;");
        sb.append("1m"); // SGR — doesn't touch cursor, so a clean run is easy to assert on
        long start = System.nanoTime();
        assertDoesNotThrow(() -> send(emu, sb.toString()));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 5_000, "expected the MAX_CSI_PARAMS cap to keep this fast, took " + elapsedMs + "ms");
        // The parser must still be functional afterwards.
        send(emu, ESC + "[H");
        assertEquals(0, emu.getCursorRow());
    }

    // -----------------------------------------------------------------------
    // Truncated sequences
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("A CSI sequence truncated mid-parameter (no final byte) neither throws nor moves the cursor")
    void truncatedCsi_noExceptionCursorUnaffected() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[10;10H");
        assertDoesNotThrow(() -> send(emu, ESC + "[3"));
        assertEquals(9, emu.getCursorRow());
        assertEquals(9, emu.getCursorCol());
    }

    @Test
    @DisplayName("A bare ESC byte with nothing following does not throw, and the next escape still starts cleanly")
    void bareEscapeByte_awaitingNextByte_thenRecovers() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        assertDoesNotThrow(() -> emu.processBytes(new byte[]{0x1B}));
        send(emu, "[5;5H");
        assertEquals(4, emu.getCursorRow());
        assertEquals(4, emu.getCursorCol());
    }

    @Test
    @DisplayName("An unrecognized escape final byte is swallowed rather than printed to the screen")
    void unrecognizedEscapeFinalByte_swallowedNotPrinted() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        assertDoesNotThrow(() -> send(emu, ESC + "Z")); // 'Z' has no case in processEscape
        assertEquals(' ', emu.getCell(0, 0, 0).character, "the escape's final byte must not be printed as text");
        assertEquals(0, emu.getCursorCol(), "cursor must not have advanced");
    }

    @Test
    @DisplayName("An OSC truncated right after its own ESC-of-ST (no trailing backslash) does not throw")
    void truncatedOscEscTerminator_noException() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        assertDoesNotThrow(() -> send(emu, ESC + "]0;abc" + ESC));
    }

    // -----------------------------------------------------------------------
    // OSC without a terminator
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("An OSC string with no terminator at all never reaches the screen")
    void oscWithoutTerminator_neverLeaksToScreen() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        assertDoesNotThrow(() -> send(emu, ESC + "]0;Hello World, this never ends"));
        assertEquals(' ', emu.getCell(0, 0, 0).character);
        assertEquals(0, emu.getCursorRow());
        assertEquals(0, emu.getCursorCol());
    }

    @Test
    @DisplayName("An unterminated OSC title is capped at MAX_OSC_LEN instead of growing without bound")
    void oscWithoutTerminator_bufferCappedAtMaxLen() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        AtomicReference<String> reported = new AtomicReference<>();
        emu.setTitleListener(reported::set);

        StringBuilder huge = new StringBuilder(ESC + "]0;");
        for (int i = 0; i < 20_000; i++) huge.append('x');
        send(emu, huge.toString());
        send(emu, ""); // BEL terminates it now

        assertNotNull(reported.get(), "title should have been reported once terminated");
        assertTrue(reported.get().length() < 20_000,
                "buffer should have been capped well below the 20000 x's sent, was " + reported.get().length());
    }

    // -----------------------------------------------------------------------
    // resize() with the cursor left outside the new bounds
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Shrinking the terminal clamps a cursor that was outside the new bounds")
    void resize_shrinking_clampsCursorInsideNewBounds() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[24;80H"); // bottom-right corner, row 23 col 79 (0-based)
        assertEquals(23, emu.getCursorRow());
        assertEquals(79, emu.getCursorCol());

        emu.resize(10, 5);

        assertEquals(4, emu.getCursorRow(), "cursor row must be clamped to the new rows-1");
        assertEquals(9, emu.getCursorCol(), "cursor col must be clamped to the new cols-1");
    }

    @Test
    @DisplayName("Growing the terminal leaves a cursor that already fit untouched")
    void resize_growing_leavesInBoundsCursorUntouched() {
        TerminalEmulator emu = new TerminalEmulator(10, 5);
        send(emu, ESC + "[5;10H"); // row 4, col 9 (0-based) — bottom-right of the small screen
        emu.resize(80, 24);
        assertEquals(4, emu.getCursorRow());
        assertEquals(9, emu.getCursorCol());
    }

    @Test
    @DisplayName("resize() does not throw for degenerate target sizes (0 or negative cols/rows)")
    void resize_degenerateSizes_doNotThrow() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        assertDoesNotThrow(() -> emu.resize(0, 0));
        assertEquals(1, emu.getCols(), "must clamp to a minimum of 1 column");
        assertEquals(1, emu.getRows(), "must clamp to a minimum of 1 row");
        assertDoesNotThrow(() -> emu.resize(-5, -5));
        assertEquals(1, emu.getCols());
        assertEquals(1, emu.getRows());
    }

    // -----------------------------------------------------------------------
    // Invalid scroll region (DECSTBM)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("DECSTBM with top >= bottom resets to the full-screen region instead of leaving an inverted one")
    void decstbm_topGreaterThanBottom_resetsToFullScreen() throws Exception {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        assertDoesNotThrow(() -> send(emu, ESC + "[20;5r")); // top=20 > bottom=5, 1-based
        assertEquals(0, scrollTop(emu), "invalid region must fall back to the full screen");
        assertEquals(23, scrollBottom(emu));
        assertEquals(0, emu.getCursorRow(), "DECSTBM always homes the cursor");
        assertEquals(0, emu.getCursorCol());
    }

    @Test
    @DisplayName("DECSTBM with top == bottom also resets to the full-screen region")
    void decstbm_topEqualsBottom_resetsToFullScreen() throws Exception {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        assertDoesNotThrow(() -> send(emu, ESC + "[5;5r"));
        assertEquals(0, scrollTop(emu));
        assertEquals(23, scrollBottom(emu));
    }

    @Test
    @DisplayName("DECSTBM with a bottom far beyond the screen height is clamped to the last row")
    void decstbm_bottomBeyondRows_clampedToLastRow() throws Exception {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        assertDoesNotThrow(() -> send(emu, ESC + "[1;99999r"));
        assertEquals(0, scrollTop(emu));
        assertEquals(23, scrollBottom(emu));
    }

    @Test
    @DisplayName("A valid, non-full-screen DECSTBM region is honored as given")
    void decstbm_validSubRegion_isHonored() throws Exception {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        assertDoesNotThrow(() -> send(emu, ESC + "[5;15r")); // rows 5-15, 1-based -> 4-14, 0-based
        assertEquals(4, scrollTop(emu));
        assertEquals(14, scrollBottom(emu));
    }

    @Test
    @DisplayName("Scrolling still works across the whole screen after an invalid region was rejected")
    void decstbm_afterInvalidRegionRejected_scrollingCoversFullScreen() {
        TerminalEmulator emu = new TerminalEmulator(80, 3); // small so a few linefeeds fill it
        send(emu, ESC + "[3;1r"); // top=3 > bottom=1 -> rejected, falls back to full screen
        // Write a marker on line 0 then force enough line feeds to scroll it into scrollback,
        // which only happens if the (fallback) region really does span the whole screen.
        send(emu, ESC + "[H");
        send(emu, "X");
        int before = emu.getScrollbackSize();
        send(emu, "\n\n\n\n");
        assertTrue(emu.getScrollbackSize() > before, "expected full-screen scroll region to push lines into scrollback");
    }

    // =========================================================================
    // Terminal-feature regression suite (builds 205, 209, 225-233 — previously
    // untested: TerminalEmulatorTest only covered parser robustness against
    // malformed input, never whether the actually-implemented VT100/xterm
    // features still behave correctly).
    // =========================================================================

    // -----------------------------------------------------------------------
    // DECALN (build 225)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("DECALN (ESC # 8) fills every visible cell with 'E' and resets scroll region + cursor")
    void decaln_fillsScreenWithE_andResetsRegionAndCursor() throws Exception {
        TerminalEmulator emu = new TerminalEmulator(10, 5);
        send(emu, ESC + "[2;4r"); // narrow the scroll region first
        send(emu, ESC + "[3;3H"); // move cursor away from home
        send(emu, ESC + "#8");
        for (int r = 0; r < 5; r++)
            for (int c = 0; c < 10; c++)
                assertEquals('E', emu.getCell(r, c, 0).character, "cell (" + r + "," + c + ") should be 'E'");
        assertEquals(0, emu.getCursorRow());
        assertEquals(0, emu.getCursorCol());
        assertEquals(0, scrollTop(emu));
        assertEquals(4, scrollBottom(emu));
    }

    // -----------------------------------------------------------------------
    // DECCOLM (build 226)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("DECCOLM (CSI ?3h) switches to 132 columns when the session opted in, clearing the screen and homing the cursor")
    void deccolm_switchesTo132Columns_whenAllowed() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        emu.setAllowColumnMode(true);
        send(emu, "x");
        send(emu, ESC + "[3;3H");
        send(emu, ESC + "[?3h");
        assertEquals(132, emu.getCols());
        assertEquals(0, emu.getCursorRow());
        assertEquals(0, emu.getCursorCol());
        assertEquals(' ', emu.getCell(0, 0, 0).character, "DECCOLM must clear the screen");
    }

    @Test
    @DisplayName("DECCOLM (CSI ?3l) switches back to 80 columns when allowed")
    void deccolm_switchesBackTo80Columns_whenAllowed() {
        TerminalEmulator emu = new TerminalEmulator(132, 24);
        emu.setAllowColumnMode(true);
        send(emu, ESC + "[?3l");
        assertEquals(80, emu.getCols());
    }

    @Test
    @DisplayName("DECCOLM is a no-op unless the session opted in via setAllowColumnMode(true)")
    void deccolm_ignoredWhenSessionHasNotOptedIn() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[?3h");
        assertEquals(80, emu.getCols(), "a remote must not be able to resize the window unless the session allows it");
    }

    // -----------------------------------------------------------------------
    // DECOM origin mode (build 227)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("DECOM on (CSI ?6h) homes to the scroll region's top margin and confines CUP to the region")
    void decom_on_homesToRegionTopAndConfinesCup() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[5;20r"); // region rows 5-20 (1-based) -> top=4, bottom=19 (0-based)
        send(emu, ESC + "[?6h");
        assertEquals(4, emu.getCursorRow(), "DECOM must home to the region's top margin, not row 0");
        send(emu, ESC + "[1;1H"); // under DECOM, row 1 is relative to the region's top margin
        assertEquals(4, emu.getCursorRow());
    }

    @Test
    @DisplayName("DECOM off (CSI ?6l, the default): CUP addressing stays screen-relative")
    void decom_off_cupIsScreenRelative() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[5;20r");
        send(emu, ESC + "[?6l");
        send(emu, ESC + "[1;1H");
        assertEquals(0, emu.getCursorRow());
    }

    // -----------------------------------------------------------------------
    // Tab stops (build 229)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("HTS (ESC H) sets a tab stop at the cursor's column; TAB advances there")
    void hts_setsTabStop_tabAdvancesThere() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[1;6H"); // column index 5 (0-based)
        send(emu, ESC + "H");     // HTS
        send(emu, ESC + "[1;1H"); // back to column 0
        send(emu, "\t");
        assertEquals(5, emu.getCursorCol());
    }

    @Test
    @DisplayName("TBC mode 3 (CSI 3g) clears every tab stop; TAB then parks at the right margin")
    void tbc_clearAll_tabParksAtRightMargin() {
        TerminalEmulator emu = new TerminalEmulator(20, 24);
        send(emu, ESC + "[3g");
        send(emu, "\t");
        assertEquals(19, emu.getCursorCol());
    }

    // -----------------------------------------------------------------------
    // DECSC/DECRC (build 229)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("DECSC/DECRC (ESC 7 / ESC 8) save and restore cursor position and SGR attributes")
    void decscDecrc_savesAndRestoresPositionAndAttrs() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[5;10H");
        send(emu, ESC + "[1m"); // bold
        send(emu, ESC + "7");    // save
        send(emu, ESC + "[1;1H");
        send(emu, ESC + "[0m");  // reset attrs
        send(emu, ESC + "8");    // restore
        assertEquals(4, emu.getCursorRow());
        assertEquals(9, emu.getCursorCol());
        send(emu, "x");
        assertTrue(emu.getCell(4, 9, 0).bold, "restored attributes must include the bold set before save");
    }

    @Test
    @DisplayName("DECSC/DECRC also save and restore origin mode and the G0 line-drawing charset selection")
    void decscDecrc_savesAndRestoresOriginModeAndCharset() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[5;20r"); // scroll region so DECOM has an observable effect
        send(emu, ESC + "[?6h");    // origin mode on
        send(emu, ESC + "(0");      // G0 = line drawing
        send(emu, ESC + "7");        // save
        send(emu, ESC + "[?6l");    // origin mode off
        send(emu, ESC + "(B");      // G0 = ASCII
        send(emu, ESC + "8");        // restore

        send(emu, ESC + "[1;1H");
        assertEquals(4, emu.getCursorRow(), "DECRC must restore origin mode (home = region top margin)");
        send(emu, "j");
        assertEquals('┘', emu.getCell(4, 0, 0).character,
                "DECRC must restore the G0 line-drawing charset selection");
    }

    // -----------------------------------------------------------------------
    // SGR blink / reverse video attributes (build 229 / 230)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("SGR 5 sets the blink attribute on written cells; SGR 25 clears it")
    void sgrBlink_setsAndClearsCellAttribute() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[5m" + "a");
        assertTrue(emu.getCell(0, 0, 0).blink);
        send(emu, ESC + "[25m" + "b");
        assertFalse(emu.getCell(0, 1, 0).blink);
    }

    @Test
    @DisplayName("SGR 7 sets the reverse attribute on written cells; SGR 27 clears it")
    void sgrReverse_setsAndClearsCellAttribute() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[7m" + "a");
        assertTrue(emu.getCell(0, 0, 0).reverse);
        send(emu, ESC + "[27m" + "b");
        assertFalse(emu.getCell(0, 1, 0).reverse);
    }

    // -----------------------------------------------------------------------
    // CSI omitted-parameter-as-zero (build 231)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("'ESC[;7m' resets attributes (omitted leading param = 0) then applies SGR 7, instead of losing the reset")
    void csiOmittedLeadingParam_sgr_isZeroNotDropped() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[1m");      // bold on
        send(emu, ESC + "[;7m" + "x"); // omitted first param = 0 (reset), then 7 (reverse)
        TerminalCell cell = emu.getCell(0, 0, 0);
        assertFalse(cell.bold, "the reset (param 0) must have cleared bold");
        assertTrue(cell.reverse, "SGR 7 after the reset must still apply");
    }

    @Test
    @DisplayName("'ESC[;5H' addresses row 1 column 5 (omitted leading param = 0), not row 5")
    void csiOmittedLeadingParam_cup_addressesRow1Col5() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[;5H");
        assertEquals(0, emu.getCursorRow());
        assertEquals(4, emu.getCursorCol());
    }

    // -----------------------------------------------------------------------
    // DECAWM autowrap (build 233)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("DECAWM on (default): writing past the right margin wraps to the next line")
    void decawmOn_wrapsToNextLine() {
        TerminalEmulator emu = new TerminalEmulator(5, 3);
        send(emu, "abcde"); // fills the line exactly
        send(emu, "f");     // one more char — should wrap
        assertEquals('f', emu.getCell(1, 0, 0).character, "the 6th char must wrap to the next line's first column");
        assertEquals(1, emu.getCursorCol());
    }

    @Test
    @DisplayName("DECAWM off (CSI ?7l): writing past the right margin overwrites the last column instead of wrapping")
    void decawmOff_overwritesLastColumn_noWrap() {
        TerminalEmulator emu = new TerminalEmulator(5, 3);
        send(emu, ESC + "[?7l");
        send(emu, "abcde");
        send(emu, "f");
        assertEquals('f', emu.getCell(0, 4, 0).character, "with autowrap off, the extra char overwrites the last column");
        assertEquals(0, emu.getCursorRow(), "must not have moved to the next line");
        assertEquals(4, emu.getCursorCol(), "cursor stays pinned at the right margin");
    }

    // -----------------------------------------------------------------------
    // Bracketed paste mode (build 209) — the mode flag itself; the actual
    // ESC[200~/ESC[201~ wrapping of pasted text happens in TerminalTab, which
    // needs a live SWT Display to exercise and is out of scope here.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("CSI ?2004h/l toggles bracketed-paste mode; RIS resets it off")
    void bracketedPaste_toggledByCsi2004_resetByRis() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        assertFalse(emu.isBracketedPaste(), "off by default");
        send(emu, ESC + "[?2004h");
        assertTrue(emu.isBracketedPaste());
        send(emu, ESC + "[?2004l");
        assertFalse(emu.isBracketedPaste());
        send(emu, ESC + "[?2004h");
        assertTrue(emu.isBracketedPaste());
        send(emu, ESC + "c"); // RIS
        assertFalse(emu.isBracketedPaste(),
                "RIS must reset bracketed-paste mode, not leave it stuck on for the next program");
    }

    // -----------------------------------------------------------------------
    // Erase fills with the CURRENT SGR background (build 205)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Erase (EL) fills with the CURRENT SGR background, not always the default")
    void eraseLine_usesCurrentSgrBackground() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[44m"); // blue background (palette index 4)
        send(emu, ESC + "[2K");  // erase whole line
        TerminalCell cell = emu.getCell(0, 0, 0);
        assertEquals(' ', cell.character);
        assertEquals(4, cell.bgColor, "erased cell must carry the CURRENT background (blue=4), not the default sentinel");
        assertEquals(TerminalEmulator.DEFAULT_COLOR, cell.fgColor, "foreground always resets to default on erase");
    }

    @Test
    @DisplayName("After SGR reset (ESC[0m), an erase leaves no background colour behind")
    void eraseLine_afterSgrReset_leavesNoBackground() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[44m");
        send(emu, ESC + "[0m"); // reset — background back to default
        send(emu, ESC + "[2K");
        assertEquals(TerminalEmulator.DEFAULT_COLOR, emu.getCell(0, 0, 0).bgColor);
    }

    // -----------------------------------------------------------------------
    // Alt-buffer nesting — the YaST-inside-MC bug (build 30)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Activating the alt buffer while already in it (nested, e.g. YaST inside MC) clears the alt buffer for the inner app")
    void altBufferNesting_nestedActivate_clearsInnerScreenOnly() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, "primary text");
        send(emu, ESC + "[?1049h"); // MC enters the alt buffer
        send(emu, "MC content");
        send(emu, ESC + "[?1049h"); // YaST enters the alt buffer too (nested)
        assertEquals(' ', emu.getCell(0, 0, 0).character, "nested activation must clear the alt buffer for the inner app");
        assertTrue(emu.isAltBufferActive());
    }

    @Test
    @DisplayName("Deactivating a NESTED alt buffer (inner app exiting) stays in alt buffer and preserves its content — the actual build-30 bug")
    void altBufferNesting_nestedDeactivate_staysInAltBuffer_preservesContent() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, ESC + "[?1049h"); // MC enters the alt buffer
        send(emu, "MC");
        send(emu, ESC + "[?1049h"); // YaST enters (nested)
        send(emu, "YaST content");
        send(emu, ESC + "[?1049l"); // YaST exits (nested deactivate)

        assertTrue(emu.isAltBufferActive(),
                "exiting the INNER app must not close the OUTER app's (MC's) alt buffer too — this "
              + "was the actual build-30 bug: a bare 'if (altBufferActive) return' with no nesting "
              + "counter meant YaST exiting also kicked MC back to the primary buffer");
        assertEquals('Y', emu.getCell(0, 0, 0).character,
                "the alt buffer must still hold whatever the inner app last drew, not be cleared or "
              + "reverted — MC does its own differential redraw from that state");
    }

    @Test
    @DisplayName("A single (non-nested) alt-buffer round trip still returns to the primary buffer with its content intact")
    void altBuffer_nonNested_stillRestoresPrimaryBufferContent() {
        TerminalEmulator emu = new TerminalEmulator(80, 24);
        send(emu, "primary");
        send(emu, ESC + "[?1049h");
        send(emu, "alt screen content");
        send(emu, ESC + "[?1049l");
        assertFalse(emu.isAltBufferActive());
        assertEquals('p', emu.getCell(0, 0, 0).character, "primary buffer content must survive an alt-buffer round trip");
    }
}
