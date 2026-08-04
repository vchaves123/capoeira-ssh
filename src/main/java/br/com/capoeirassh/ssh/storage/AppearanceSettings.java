package br.com.capoeirassh.ssh.storage;

import org.eclipse.swt.graphics.RGB;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;
import java.io.ByteArrayOutputStream;

public class AppearanceSettings {

    private static final Path FILE = Path.of(
            System.getProperty("user.home"), ".capoeira", "appearance.properties");

    private static int    fontSize = 12;
    private static String fontName = "Consolas";
    private static RGB fgColor  = new RGB(232, 184, 75);  // Ouro #E8B84B
    private static RGB bgColor  = new RGB(0,   0,   0  );

    /** Schema version of appearance.properties, written by save() below. Absent on every file
     *  written before this field existed — load() treats a missing value as compatible, but
     *  refuses (keeps the built-in defaults above) a value greater than SCHEMA_VERSION, i.e. a
     *  file written by a future version of this program in a format this build doesn't
     *  understand — see the identical fix in SessionStorage for the full rationale. */
    private static final int SCHEMA_VERSION = 1;

    static { load(); }

    public static int    getFontSize() { return fontSize; }
    public static String getFontName() { return fontName; }
    public static RGB getFgColor()  { return new RGB(fgColor.red, fgColor.green, fgColor.blue); }
    public static RGB getBgColor()  { return new RGB(bgColor.red, bgColor.green, bgColor.blue); }

    public static void set(String font, int size, RGB fg, RGB bg) {
        fontName = (font != null && !font.isBlank()) ? font : "Consolas";
        fontSize = size;
        fgColor  = new RGB(fg.red, fg.green, fg.blue);
        bgColor  = new RGB(bg.red, bg.green, bg.blue);
        save();
    }

    private static void load() {
        if (!Files.exists(FILE)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(FILE)) {
            p.load(in);
        } catch (Exception ignored) {
            return;
        }

        // A file with no schemaVersion is one written before this field existed — treated
        // as compatible. A present value greater than SCHEMA_VERSION means a future version
        // wrote it in a format this build doesn't understand; refuse it (keep the built-in
        // defaults) rather than silently applying whatever partial/wrong values result from
        // keys this build doesn't recognize. An unparseable version string is treated as
        // compatible too — it's far more likely to be accidental file corruption than a real
        // future-version marker, and the per-field fallbacks below already handle that safely.
        String verStr = p.getProperty("schemaVersion");
        if (verStr != null && parseIntOr(verStr, SCHEMA_VERSION) > SCHEMA_VERSION) return;

        // Each field falls back to its own default independently — parseIntOr()/parseRgb()
        // never throw — so one corrupted value (e.g. a hand-edited or truncated fontSize) no
        // longer discards every other still-valid field in the file, unlike the single
        // all-or-nothing try/catch this replaced.
        fontSize = parseIntOr(p.getProperty("fontSize", "12"), 12);
        fontName = p.getProperty("fontName", "Consolas");
        fgColor  = parseRgb(p.getProperty("fgColor", "255,176,0"));
        bgColor  = parseRgb(p.getProperty("bgColor", "0,0,0"));
    }

    private static int parseIntOr(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static void save() {
        Properties p = new Properties();
        p.setProperty("schemaVersion", String.valueOf(SCHEMA_VERSION));
        p.setProperty("fontSize", String.valueOf(fontSize));
        p.setProperty("fontName", fontName);
        p.setProperty("fgColor",  fgColor.red + "," + fgColor.green + "," + fgColor.blue);
        p.setProperty("bgColor",  bgColor.red + "," + bgColor.green + "," + bgColor.blue);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            p.store(baos, null);
            SecureFiles.write(FILE, baos.toByteArray());
        } catch (IOException ignored) {}
    }

    private static RGB parseRgb(String s) {
        try {
            String[] parts = s.split(",");
            return new RGB(Integer.parseInt(parts[0].trim()),
                           Integer.parseInt(parts[1].trim()),
                           Integer.parseInt(parts[2].trim()));
        } catch (Exception e) {
            return new RGB(204, 204, 204);
        }
    }
}
