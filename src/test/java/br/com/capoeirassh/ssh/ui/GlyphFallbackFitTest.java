package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.terminal.TerminalEmulator;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code GlyphFallback} substitutes a font for code points the terminal's own font has no glyph
 * for (build 232's motivating case: U+23F5 "⏵", used in Claude Code's status line, missing from
 * every common monospace family on Windows). Substitute fonts are proportional, so at the
 * terminal's own point size their glyphs are routinely wider than a monospace cell — {@code
 * fitFor()} measures the glyph and shrinks the point size until it fits, computing a centring
 * x-offset alongside it. Never had a test: this is the "substitute glyph cell sizing" item the
 * regression-test plan had marked out of scope, on the assumption it needed a full {@code
 * TerminalTab} render harness. It doesn't — {@code GlyphFallback} is package-visible and only
 * needs a real {@code Display} to measure fonts with, which this test constructs directly (no
 * screenshot/golden-image comparison, which would be fragile across font-hinting/OS differences
 * and wouldn't actually remove the {@code Display} dependency anyway — see conversation).
 *
 * <p>No production code is modified.
 */
@Tag("ui") // needs a real SWT Display — no display on the Linux/macOS CI runners
class GlyphFallbackFitTest {

    private static Display display;
    private static Font terminalFont;
    private static int cellWidth;

    @BeforeAll
    static void setUp() {
        display = new Display();
        terminalFont = new Font(display, "Consolas", 14, org.eclipse.swt.SWT.NORMAL);
        GC gc = new GC(display);
        try {
            gc.setFont(terminalFont);
            cellWidth = (int) Math.round(gc.getFontMetrics().getAverageCharacterWidth());
        } finally {
            gc.dispose();
        }
    }

    @AfterAll
    static void tearDown() {
        if (terminalFont != null && !terminalFont.isDisposed()) terminalFont.dispose();
        if (display != null && !display.isDisposed()) display.dispose();
    }

    @Test
    void fontFor_asciiCodePoint_neverNeedsSubstitution() {
        GlyphFallback gf = new GlyphFallback(display, "Consolas", 14, cellWidth);
        try {
            assertNull(gf.fontFor('A', false), "an ASCII code point must always use the terminal's own font");
            assertEquals(0, gf.xOffsetFor('A'), "no substitution means no centring offset either");
        } finally {
            gf.dispose();
        }
    }

    @Test
    void fontFor_glyphMissingFromTerminalFont_isShrunkToFitTheCellWidth() {
        GlyphFallback gf = new GlyphFallback(display, "Consolas", 14, cellWidth);
        try {
            int codePoint = 0x23F5; // ⏵ BLACK RIGHT-POINTING DOUBLE TRIANGLE — the class's own motivating example
            Font substitute = gf.fontFor(codePoint, false);
            Assumptions.assumeTrue(substitute != null,
                    "no substitute font available on this machine (AWT unusable, or no candidate "
                  + "family installed) — this test only makes sense where GlyphFallback is actually active");

            String glyph = new String(Character.toChars(codePoint));
            int allowed = cellWidth * TerminalEmulator.charWidth(codePoint); // width 1 for this code point

            GC gc = new GC(display);
            int measuredWidth;
            try {
                gc.setFont(substitute);
                measuredWidth = gc.stringExtent(glyph).x;
            } finally {
                gc.dispose();
            }

            assertTrue(measuredWidth <= allowed,
                    "the substitute glyph, measured with the font fitFor() actually chose, must fit "
                  + "within the cell (" + measuredWidth + "px measured vs " + allowed + "px allowed) — "
                  + "this is the actual build-232 bug: an unfit substitute spilling past the cell, "
                  + "and in reverse video the overhang landing outside the painted background and "
                  + "vanishing, leaving half a glyph");

            int xoff = gf.xOffsetFor(codePoint);
            assertTrue(xoff >= 0 && xoff + measuredWidth <= allowed,
                    "the centring x-offset (" + xoff + ") plus the glyph's measured width must still "
                  + "land inside the cell, not push it back out past the right edge");
        } finally {
            gf.dispose();
        }
    }

    @Test
    void fontFor_sameCodePointQueriedTwice_returnsTheSameCachedFont() {
        GlyphFallback gf = new GlyphFallback(display, "Consolas", 14, cellWidth);
        try {
            int codePoint = 0x23F5;
            Font first = gf.fontFor(codePoint, false);
            Assumptions.assumeTrue(first != null, "no substitute font available on this machine");
            Font second = gf.fontFor(codePoint, false);
            assertSame(first, second, "repeated lookups for the same code point must reuse the cached "
                  + "Font instance, not allocate a new native font handle every time (same class of "
                  + "concern as the cached termFontBold in TerminalTab.render(), build 138/#24)");
        } finally {
            gf.dispose();
        }
    }

    @Test
    void dispose_disposesEveryCachedFont() {
        GlyphFallback gf = new GlyphFallback(display, "Consolas", 14, cellWidth);
        Font substitute = gf.fontFor(0x23F5, false);
        Assumptions.assumeTrue(substitute != null, "no substitute font available on this machine");
        gf.dispose();
        assertTrue(substitute.isDisposed(), "dispose() must dispose every Font it created, not leak native handles");
    }
}
