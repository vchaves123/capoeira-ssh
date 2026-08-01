package br.com.capoeirassh.ssh.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.widgets.Display;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Picks a substitute font for code points the terminal's chosen font has no glyph for, so they
 * render instead of appearing as a "tofu" box.
 *
 * This is needed more often than it sounds: {@code U+23F5} (⏵), which Claude Code prints in its
 * status line, is absent from every monospace family on a typical Windows install — Consolas,
 * Courier New, Lucida Console, Ubuntu Mono, and even the JetBrains Mono Nerd Font patches. Only
 * Segoe UI Symbol carries it. Terminals that render such text correctly (Windows Terminal,
 * VS Code) all do per-glyph fallback like this.
 *
 * Coverage is queried through {@link java.awt.Font#canDisplay(int)} — SWT exposes no equivalent,
 * and measuring glyph widths to infer coverage is unreliable because the platform silently
 * substitutes fonts during measurement. AWT is used purely as a font-metadata oracle here: no
 * AWT window, image, or event loop is ever created. Every AWT call is guarded, and if AWT is
 * unusable the class degrades to "no fallback", i.e. exactly the previous behaviour.
 */
final class GlyphFallback {

    /** Substitutes tried in order; the first installed one that has the glyph wins. */
    private static final String[] CANDIDATES_WIN = {
        "Segoe UI Symbol", "Segoe UI Emoji", "Cascadia Mono", "Arial Unicode MS", "Segoe UI"
    };
    private static final String[] CANDIDATES_MAC = {
        "Apple Symbols", "Apple Color Emoji", "Menlo", "Arial Unicode MS"
    };
    private static final String[] CANDIDATES_NIX = {
        "Noto Sans Symbols 2", "Noto Sans Symbols", "Noto Color Emoji", "Symbola",
        "DejaVu Sans", "FreeSerif"
    };

    /** Marks "the terminal font already covers this" in {@link #choice}. */
    private static final int PRIMARY = -1;

    /** Never shrink a substitute below this, however wide its glyph is — an unreadable speck is
     *  worse than a glyph that overhangs its cell slightly. */
    private static final int MIN_POINTS = 6;

    private final Display display;
    private final int     size;
    /** Width of one terminal cell in pixels; 0 disables fitting (sizes are left untouched). */
    private final int     cellWidth;

    /** Terminal font as AWT sees it, for coverage queries; null disables fallback entirely. */
    private java.awt.Font awtPrimary;

    private final List<String>        familyNames = new ArrayList<>();
    private final List<java.awt.Font> awtFamilies = new ArrayList<>();

    /** Code point → index into the family lists, or {@link #PRIMARY}. Populated on demand;
     *  render() consults this per visible cell, so the lookup must stay cheap. */
    private final Map<Integer, Integer> choice = new HashMap<>();

    /** Code point → the point size its substitute must use to fit the cell, and the x offset
     *  that then centres it there. Substitutes are proportional fonts (Segoe UI Symbol and
     *  friends), so at the terminal's own point size their glyphs are routinely wider than a
     *  monospace cell — a diamond drawn that way spills past the cell and, in reverse video,
     *  the overhang lands outside the painted background and vanishes, leaving half a glyph. */
    private final Map<Integer, Integer> fittedSize = new HashMap<>();
    private final Map<Integer, Integer> fittedXOff = new HashMap<>();

    /** (family, points, bold) → font. Keyed rather than indexed now that one family can be
     *  needed at several sizes. */
    private final Map<Long, Font> fonts = new HashMap<>();

    GlyphFallback(Display display, String primaryFamily, int size, int cellWidth) {
        this.display   = display;
        this.size      = size;
        this.cellWidth = cellWidth;

        try {
            java.awt.Font probe = new java.awt.Font(primaryFamily, java.awt.Font.PLAIN, 12);
            // An unknown family silently becomes "Dialog", whose coverage says nothing about the
            // font actually being drawn — treating that as authoritative would substitute glyphs
            // the terminal font can render perfectly well. Bail out instead.
            awtPrimary = probe.getFamily().equalsIgnoreCase(primaryFamily) ? probe : null;

            if (awtPrimary != null) {
                for (String name : candidates()) {
                    if (name.equalsIgnoreCase(primaryFamily)) continue;
                    if (display.getFontList(name, true).length == 0) continue;
                    java.awt.Font f = new java.awt.Font(name, java.awt.Font.PLAIN, 12);
                    if (!f.getFamily().equalsIgnoreCase(name)) continue;
                    familyNames.add(name);
                    awtFamilies.add(f);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            awtPrimary = null;   // no AWT here — fall back to "no fallback"
        }
    }

    private static String[] candidates() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return CANDIDATES_WIN;
        if (os.contains("mac")) return CANDIDATES_MAC;
        return CANDIDATES_NIX;
    }

    /**
     * The font to draw {@code codePoint} with, or null to use the terminal font. Returns null
     * for everything when fallback is unavailable, and for any code point no candidate covers —
     * drawing a tofu box in the terminal font is the honest outcome there.
     */
    Font fontFor(int codePoint, boolean bold) {
        // ASCII is covered by any usable terminal font; skipping the map here keeps the common
        // case free of a lookup and of Integer boxing.
        if (codePoint < 0x80 || awtPrimary == null) return null;

        Integer idx = choice.get(codePoint);
        if (idx == null) {
            idx = resolve(codePoint);
            choice.put(codePoint, idx);
        }
        if (idx == PRIMARY) return null;

        return font(idx, fitFor(idx, codePoint), bold);
    }

    /**
     * Pixels to shift this code point's glyph right so it sits centred in the space it occupies
     * (one cell, or two for a double-width character). 0 for anything drawn in the terminal font.
     */
    int xOffsetFor(int codePoint) {
        if (codePoint < 0x80 || awtPrimary == null) return 0;
        Integer idx = choice.get(codePoint);
        if (idx == null || idx == PRIMARY) return 0;
        fitFor(idx, codePoint);                       // ensures the offset is computed
        Integer off = fittedXOff.get(codePoint);
        return off == null ? 0 : off;
    }

    /**
     * Largest point size, no bigger than the terminal's, whose glyph for this code point fits the
     * columns it is entitled to. Measured once per code point — {@code render()} runs this lookup
     * per visible cell, so the measurement itself must never be on that path.
     */
    private int fitFor(int idx, int codePoint) {
        Integer cached = fittedSize.get(codePoint);
        if (cached != null) return cached;

        int pts     = size;
        int xoff    = 0;
        // A double-width character legitimately owns two cells; fitting it into one would shrink
        // emoji and CJK for no reason.
        int allowed = cellWidth * Math.max(1, br.com.capoeirassh.ssh.terminal.TerminalEmulator.charWidth(codePoint));

        if (cellWidth > 0) {
            String glyph = new String(Character.toChars(codePoint));
            GC gc = new GC(display);
            try {
                for (int p = size; p >= MIN_POINTS; p--) {
                    gc.setFont(font(idx, p, false));
                    int w = gc.stringExtent(glyph).x;
                    if (w <= allowed) { pts = p; xoff = (allowed - w) / 2; break; }
                    pts = MIN_POINTS;   // nothing fitted — keep the floor
                }
            } catch (RuntimeException e) {
                pts = size; xoff = 0;   // measurement unavailable — leave it alone
            } finally {
                gc.dispose();
            }
        }

        fittedSize.put(codePoint, pts);
        fittedXOff.put(codePoint, xoff);
        return pts;
    }

    private Font font(int idx, int points, boolean bold) {
        long key = (((long) idx * 1024 + points) << 1) | (bold ? 1 : 0);
        Font f = fonts.get(key);
        if (f == null || f.isDisposed()) {
            f = new Font(display, familyNames.get(idx), points, bold ? SWT.BOLD : SWT.NORMAL);
            fonts.put(key, f);
        }
        return f;
    }

    private int resolve(int codePoint) {
        try {
            if (awtPrimary.canDisplay(codePoint)) return PRIMARY;
            for (int i = 0; i < awtFamilies.size(); i++) {
                if (awtFamilies.get(i).canDisplay(codePoint)) return i;
            }
        } catch (RuntimeException e) {
            // Treat a failed query as "covered" so a broken font never stops rendering.
        }
        return PRIMARY;
    }

    void dispose() {
        for (Font f : fonts.values()) if (f != null && !f.isDisposed()) f.dispose();
        fonts.clear();
        choice.clear();
        fittedSize.clear();
        fittedXOff.clear();
    }
}
