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

    public static BufferedImage buildIcon(int sz) {
        BufferedImage img = new BufferedImage(sz, sz, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,   RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int r = Math.max(2, sz / 8);

        // Outer bezel — dark charcoal
        g.setColor(new Color(22, 27, 34));
        g.fill(new RoundRectangle2D.Float(0, 0, sz, sz, r * 2, r * 2));

        // Inner screen area
        int pad = Math.max(1, sz / 10);
        g.setColor(new Color(13, 17, 23));
        g.fill(new RoundRectangle2D.Float(pad, pad, sz - pad * 2, sz - pad * 2, r, r));

        // Top-edge teal highlight
        g.setColor(new Color(0, 200, 160));
        g.drawLine(pad + r, pad, sz - pad - r, pad);

        // Prompt ">" in bright green
        int fontSize = Math.max(6, sz * 5 / 16);
        g.setFont(new Font("Monospaced", Font.BOLD, fontSize));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(new Color(0, 255, 140));
        int tx = pad + Math.max(1, sz / 8);
        int ty = sz / 2 - fm.getHeight() / 2 - Math.max(1, sz / 16) + fm.getAscent();
        g.drawString(">", tx, ty);

        // Cursor underscore — white
        int strW = fm.stringWidth(">");
        int cx   = tx + strW + Math.max(1, sz / 16);
        int cy   = ty + Math.max(1, sz / 16);
        int cw   = Math.max(2, sz / 5);
        int lw   = Math.max(1, sz / 16);
        g.setColor(new Color(220, 220, 220));
        g.setStroke(new BasicStroke(lw));
        g.drawLine(cx, cy, cx + cw, cy);

        // Subtle scan-line
        int sl = pad + (sz - pad * 2) * 2 / 3;
        g.setColor(new Color(255, 255, 255, 18));
        g.drawLine(pad + 1, sl, sz - pad - 1, sl);

        g.dispose();
        return img;
    }
}
