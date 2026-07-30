package br.com.capoeirassh.ssh.ui;

import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.widgets.Display;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Enumerates the monospace fonts actually installed on this system, so any font the user
 * installs — a Nerd Font, for instance, which carries the box-drawing and icon glyphs modern
 * CLIs use — shows up in the appearance dialog without needing to be hardcoded here first.
 */
final class MonoFonts {

    private MonoFonts() {}

    /** Default font name used when nothing else is configured. */
    static final String DEFAULT = "Consolas";

    /** Tried in this order when the configured font is unavailable. */
    private static final String[] PREFERRED = {
        "Consolas", "Cascadia Mono", "Cascadia Code", "Lucida Console",
        "Courier New", "DejaVu Sans Mono", "Liberation Mono", "Monospace",
        "Menlo", "Monaco", "SF Mono", "Courier",
    };

    /**
     * Fonts that pass the fixed-width test but are useless as a terminal face: symbol and
     * dingbat faces (their "letters" are icons), and the legacy Windows UI symbol fonts.
     */
    private static final Set<String> EXCLUDED = Set.of(
        "marlett", "symbol", "webdings", "wingdings", "wingdings 2", "wingdings 3",
        "ms outlook", "mt extra", "bookshelf symbol 7", "holomdl2assets",
        "segoe mdl2 assets", "segoe fluent icons", "segoe ui emoji", "segoe ui symbol",
        "opensymbol", "zapf dingbats", "dingbats"
    );

    /** Cached result — enumerating and measuring every installed family is not cheap, and the
     *  set of installed fonts does not change while a dialog is open. */
    private static List<String> cached;

    /**
     * Every installed monospace family, preferred names first, then the rest alphabetically.
     * Detection measures a narrow and a wide glyph: in a fixed-width face they are identical.
     */
    static synchronized List<String> available(Display display) {
        if (cached != null) return new ArrayList<>(cached);

        Set<String> families = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (FontData fd : display.getFontList(null, true)) {
            String name = fd.getName();
            if (name == null || name.isBlank()) continue;
            // '@Name' is Windows' vertical-writing variant of a CJK family — same glyphs
            // rotated, never wanted as a terminal face.
            if (name.startsWith("@")) continue;
            if (EXCLUDED.contains(name.toLowerCase())) continue;
            families.add(name);
        }

        List<String> mono = new ArrayList<>();
        GC gc = new GC(display);
        try {
            for (String name : families) {
                if (isMonospaced(display, gc, name)) mono.add(name);
            }
        } finally {
            gc.dispose();
        }

        // Surface the well-known faces first; everything else keeps alphabetical order.
        List<String> ordered = new ArrayList<>();
        for (String p : PREFERRED) {
            for (String m : mono) {
                if (m.equalsIgnoreCase(p)) { ordered.add(m); break; }
            }
        }
        for (String m : mono) {
            if (ordered.stream().noneMatch(o -> o.equalsIgnoreCase(m))) ordered.add(m);
        }

        cached = ordered;
        return new ArrayList<>(ordered);
    }

    /** True when a narrow and a wide glyph advance the same width — i.e. a fixed-width face. */
    private static boolean isMonospaced(Display display, GC gc, String name) {
        Font font = null;
        try {
            font = new Font(display, name, 11, 0);
            gc.setFont(font);
            int narrow = gc.stringExtent("i").x;
            int wide   = gc.stringExtent("W").x;
            return narrow > 0 && narrow == wide;
        } catch (RuntimeException e) {
            return false;   // font the platform reports but cannot instantiate
        } finally {
            if (font != null) font.dispose();
        }
    }

    /** Picks a real, installed font name for the given preference, falling back sensibly. */
    static String resolve(Display display, String preferred) {
        if (preferred != null && !preferred.isBlank()
                && display.getFontList(preferred, true).length > 0) {
            return preferred;
        }
        for (String p : PREFERRED) {
            if (display.getFontList(p, true).length > 0) return p;
        }
        List<String> found = available(display);
        return found.isEmpty() ? "Courier New" : found.get(0);
    }
}
