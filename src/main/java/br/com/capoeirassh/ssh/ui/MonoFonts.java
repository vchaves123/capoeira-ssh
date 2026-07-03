package br.com.capoeirassh.ssh.ui;

import org.eclipse.swt.widgets.Display;

import java.util.ArrayList;
import java.util.List;

/** Detects which common monospace fonts are actually installed on this system. */
final class MonoFonts {

    private MonoFonts() {}

    /** Default font name used when nothing else is configured. */
    static final String DEFAULT = "Consolas";

    private static final String[] CANDIDATES = {
        "Consolas", "Cascadia Mono", "Cascadia Code", "Lucida Console",
        "Courier New", "DejaVu Sans Mono", "Liberation Mono", "Monospace",
        "Menlo", "Monaco", "SF Mono", "Courier",
    };

    /** Returns the subset of CANDIDATES actually available, in priority order. */
    static List<String> available(Display display) {
        List<String> out = new ArrayList<>();
        for (String name : CANDIDATES) {
            if (display.getFontList(name, true).length > 0) out.add(name);
        }
        return out;
    }

    /** Picks a real, installed font name for the given preference, falling back sensibly. */
    static String resolve(Display display, String preferred) {
        if (preferred != null && !preferred.isBlank()
                && display.getFontList(preferred, true).length > 0) {
            return preferred;
        }
        List<String> found = available(display);
        return found.isEmpty() ? "Courier New" : found.get(0);
    }
}
