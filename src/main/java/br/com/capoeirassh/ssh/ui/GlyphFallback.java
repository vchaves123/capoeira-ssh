package br.com.capoeirassh.ssh.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
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

    private final Display display;
    private final int     size;

    /** Terminal font as AWT sees it, for coverage queries; null disables fallback entirely. */
    private java.awt.Font awtPrimary;

    private final List<String>        familyNames = new ArrayList<>();
    private final List<java.awt.Font> awtFamilies = new ArrayList<>();
    private final Font[]              swtPlain;
    private final Font[]              swtBold;

    /** Code point → index into the family lists, or {@link #PRIMARY}. Populated on demand;
     *  render() consults this per visible cell, so the lookup must stay cheap. */
    private final Map<Integer, Integer> choice = new HashMap<>();

    GlyphFallback(Display display, String primaryFamily, int size) {
        this.display = display;
        this.size    = size;

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

        swtPlain = new Font[familyNames.size()];
        swtBold  = new Font[familyNames.size()];
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

        Font[] cache = bold ? swtBold : swtPlain;
        if (cache[idx] == null || cache[idx].isDisposed()) {
            cache[idx] = new Font(display, familyNames.get(idx), size, bold ? SWT.BOLD : SWT.NORMAL);
        }
        return cache[idx];
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
        for (Font f : swtPlain) if (f != null && !f.isDisposed()) f.dispose();
        for (Font f : swtBold)  if (f != null && !f.isDisposed()) f.dispose();
        choice.clear();
    }
}
