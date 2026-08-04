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
}
