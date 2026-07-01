package br.com.quatorzebis.ssh;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

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
        System.out.println("Icons written to " + outDir);
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
