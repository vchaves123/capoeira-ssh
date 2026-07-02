package br.com.quatorzebis.ssh.storage;

import org.eclipse.swt.graphics.RGB;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;
import java.io.ByteArrayOutputStream;

public class AppearanceSettings {

    private static final Path FILE = Path.of(
            System.getProperty("user.home"), ".14bis", "appearance.properties");

    private static int    fontSize = 12;
    private static String fontName = "Consolas";
    private static RGB fgColor  = new RGB(255, 176, 0);   // classic amber phosphor
    private static RGB bgColor  = new RGB(0,   0,   0  );

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
            fontSize = Integer.parseInt(p.getProperty("fontSize", "12"));
            fontName = p.getProperty("fontName", "Consolas");
            fgColor  = parseRgb(p.getProperty("fgColor", "255,176,0"));
            bgColor  = parseRgb(p.getProperty("bgColor", "0,0,0"));
        } catch (Exception ignored) {}
    }

    private static void save() {
        Properties p = new Properties();
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
