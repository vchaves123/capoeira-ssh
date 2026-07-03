package br.com.capoeirassh.ssh;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.*;

/**
 * Renders the app icon to PNG files using AWT (no SWT dependency).
 * Called by the Maven exec plugin during the package phase.
 */
public class IconExporter {

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : "target";
        new File(outDir).mkdirs();

        for (int sz : new int[]{16, 32, 48, 64, 128, 256}) {
            BufferedImage img = buildIcon(sz);
            ImageIO.write(img, "PNG", new File(outDir, "icon-" + sz + ".png"));
        }
        // Primary icon used by jpackage
        BufferedImage icon = buildIcon(256);
        ImageIO.write(icon, "PNG", new File(outDir, "app-icon.png"));

        // Windows .ico consumed by jpackage --icon. Must be built with BMP/DIB entries for the
        // small sizes (16/32/48) — the Windows shell only decodes PNG-in-ICO at 256, and jpackage
        // copies the icon bytes into the launcher .exe verbatim, so an all-PNG .ico leaves the exe
        // with un-renderable small icons and Windows falls back to the generic launcher icon.
        writeIco(new File(outDir, "app-icon.ico"), new int[]{16, 32, 48, 256});

        System.out.println("Icons written to " + outDir);
    }

    /**
     * Writes a multi-resolution Windows .ico: small sizes as uncompressed 32-bit BMP/DIB
     * (the only format the shell/exe icon loader reliably renders at 16/32/48), and the
     * 256×256 entry as PNG (the one size where PNG-in-ICO is standard).
     */
    public static void writeIco(File out, int[] sizes) throws IOException {
        byte[][] payload = new byte[sizes.length][];
        for (int i = 0; i < sizes.length; i++) {
            BufferedImage img = buildIcon(sizes[i]);
            if (sizes[i] >= 256) {
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                ImageIO.write(img, "PNG", png);
                payload[i] = png.toByteArray();
            } else {
                payload[i] = dibEntry(img);
            }
        }
        try (DataOutputStream d = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(out)))) {
            // ICONDIR
            writeLE16(d, 0);              // reserved
            writeLE16(d, 1);              // type = icon
            writeLE16(d, sizes.length);  // image count
            // ICONDIRENTRY[] — payloads follow the whole directory
            int offset = 6 + sizes.length * 16;
            for (int i = 0; i < sizes.length; i++) {
                int sz = sizes[i];
                d.writeByte(sz >= 256 ? 0 : sz);   // bWidth  (0 means 256)
                d.writeByte(sz >= 256 ? 0 : sz);   // bHeight (0 means 256)
                d.writeByte(0);                    // bColorCount
                d.writeByte(0);                    // bReserved
                writeLE16(d, 1);                   // wPlanes
                writeLE16(d, 32);                  // wBitCount
                writeLE32(d, payload[i].length);   // dwBytesInRes
                writeLE32(d, offset);              // dwImageOffset
                offset += payload[i].length;
            }
            for (byte[] p : payload) d.write(p);
        }
    }

    /**
     * Encodes one BMP/DIB icon image: a 40-byte BITMAPINFOHEADER (biHeight doubled to cover the
     * AND mask), a 32-bit BGRA XOR bitmap written bottom-up, then a zeroed 1-bpp AND mask whose
     * rows are padded to a 4-byte boundary (the alpha channel governs transparency).
     */
    private static byte[] dibEntry(BufferedImage img) throws IOException {
        int w = img.getWidth(), h = img.getHeight();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bos);

        writeLE32(d, 40);      // biSize
        writeLE32(d, w);       // biWidth
        writeLE32(d, h * 2);   // biHeight (XOR bitmap + AND mask)
        writeLE16(d, 1);       // biPlanes
        writeLE16(d, 32);      // biBitCount
        writeLE32(d, 0);       // biCompression = BI_RGB
        writeLE32(d, 0);       // biSizeImage (0 allowed for BI_RGB)
        writeLE32(d, 0);       // biXPelsPerMeter
        writeLE32(d, 0);       // biYPelsPerMeter
        writeLE32(d, 0);       // biClrUsed
        writeLE32(d, 0);       // biClrImportant

        // XOR bitmap: bottom-up rows, 4 bytes/pixel in B,G,R,A order
        for (int y = h - 1; y >= 0; y--) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                d.writeByte(argb & 0xFF);          // B
                d.writeByte((argb >> 8) & 0xFF);   // G
                d.writeByte((argb >> 16) & 0xFF);  // R
                d.writeByte((argb >> 24) & 0xFF);  // A
            }
        }
        // AND mask: 1 bit/pixel, each row padded to a 4-byte boundary, all zero.
        int maskRowBytes = ((w + 31) / 32) * 4;
        byte[] maskRow = new byte[maskRowBytes];
        for (int y = 0; y < h; y++) d.write(maskRow);

        return bos.toByteArray();
    }

    private static void writeLE16(DataOutputStream d, int v) throws IOException {
        d.writeByte(v & 0xFF);
        d.writeByte((v >> 8) & 0xFF);
    }

    private static void writeLE32(DataOutputStream d, int v) throws IOException {
        d.writeByte(v & 0xFF);
        d.writeByte((v >> 8) & 0xFF);
        d.writeByte((v >> 16) & 0xFF);
        d.writeByte((v >> 24) & 0xFF);
    }

    // ── Capoeira brand palette ────────────────────────────────────────────────
    private static final Color C_BG        = new Color(26,  24,  20);   // Noite #1a1814
    private static final Color C_GOLD      = new Color(232, 184, 75);   // Ouro  #E8B84B
    private static final Color C_TERRACOTA = new Color(192, 94,  26);   // Terracota #C05E1A
    private static final Color C_RING      = new Color(232, 184, 75, 110);

    public static BufferedImage buildIcon(int sz) {
        BufferedImage img = new BufferedImage(sz, sz, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,    RenderingHints.VALUE_STROKE_PURE);

        int r = Math.max(3, sz * 18 / 100);

        // Rounded background — Noite
        g.setColor(C_BG);
        g.fill(new RoundRectangle2D.Float(0, 0, sz, sz, r * 2, r * 2));

        float cx = sz / 2f, cy = sz / 2f;
        float radius = sz * 0.40f;   // roda ring radius

        // Dashed roda ring
        if (sz >= 32) {
            float dash = sz * 0.07f;
            g.setColor(C_RING);
            g.setStroke(new BasicStroke(Math.max(0.8f, sz / 48f),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    1f, new float[]{dash, dash * 0.8f}, 0));
            g.draw(new java.awt.geom.Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2));
        }

        // 6 spectator dots around the ring
        if (sz >= 24) {
            g.setColor(new Color(232, 184, 75, 140));
            int dotR = Math.max(1, sz / 22);
            for (int i = 0; i < 6; i++) {
                double angle = Math.toRadians(i * 60 - 90);
                float dx = cx + (float)(radius * Math.cos(angle));
                float dy = cy + (float)(radius * Math.sin(angle));
                g.fillOval(Math.round(dx - dotR), Math.round(dy - dotR), dotR * 2, dotR * 2);
            }
        }

        // Stroke width for figures
        float sw = Math.max(1f, sz / 16f);
        g.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        // ── Player A (gold) — left, ginga leaning right ───────────────────
        float u = sz / 80f;   // scale unit (icon is designed on 80px grid)
        drawStickFigure(g, C_GOLD, u,
            28, 26,   // head cx,cy
            28, 31, 32, 44,   // torso
            30, 37, 40, 34,   // arm forward
            29, 36, 19, 32,   // arm back
            32, 44, 26, 56,   // leg front upper
            26, 56, 21, 63,   // leg front lower
            32, 44, 36, 56,   // leg back upper
            36, 56, 32, 63);  // leg back lower

        // ── Player B (terracota) — right, mirror facing A ─────────────────
        drawStickFigure(g, C_TERRACOTA, u,
            52, 26,
            52, 31, 48, 44,
            50, 37, 40, 34,
            51, 36, 61, 32,
            48, 44, 54, 56,
            54, 56, 59, 63,
            48, 44, 44, 56,
            44, 56, 48, 63);

        g.dispose();
        return img;
    }

    private static void drawStickFigure(Graphics2D g, Color color, float u,
            float hx, float hy,
            float t1x, float t1y, float t2x, float t2y,
            float af1x, float af1y, float af2x, float af2y,
            float ab1x, float ab1y, float ab2x, float ab2y,
            float lf1x, float lf1y, float lf2x, float lf2y,
            float lf3x, float lf3y, float lf4x, float lf4y,
            float lb1x, float lb1y, float lb2x, float lb2y,
            float lb3x, float lb3y, float lb4x, float lb4y) {
        float headR = u * 4.5f;
        g.setColor(color);
        g.fill(new java.awt.geom.Ellipse2D.Float(hx * u - headR, hy * u - headR, headR * 2, headR * 2));
        g.draw(new java.awt.geom.Line2D.Float(t1x*u,  t1y*u,  t2x*u,  t2y*u));
        g.draw(new java.awt.geom.Line2D.Float(af1x*u, af1y*u, af2x*u, af2y*u));
        g.draw(new java.awt.geom.Line2D.Float(ab1x*u, ab1y*u, ab2x*u, ab2y*u));
        g.draw(new java.awt.geom.Line2D.Float(lf1x*u, lf1y*u, lf2x*u, lf2y*u));
        g.draw(new java.awt.geom.Line2D.Float(lf3x*u, lf3y*u, lf4x*u, lf4y*u));
        g.draw(new java.awt.geom.Line2D.Float(lb1x*u, lb1y*u, lb2x*u, lb2y*u));
        g.draw(new java.awt.geom.Line2D.Float(lb3x*u, lb3y*u, lb4x*u, lb4y*u));
    }
}
