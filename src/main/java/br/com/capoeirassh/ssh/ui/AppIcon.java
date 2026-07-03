package br.com.capoeirassh.ssh.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/** Shared application icon builder — Capoeira Roda design. */
public final class AppIcon {

    private AppIcon() {}

    /** Sets 16×16 and 32×32 icons on a Shell. Disposes them when the shell is disposed. */
    public static void apply(Shell shell) {
        Display display = shell.getDisplay();
        Image[] icons = { build(display, 16), build(display, 32) };
        shell.setImages(icons);
        shell.addListener(SWT.Dispose, e -> { for (Image i : icons) i.dispose(); });
    }

    public static Image build(Display display, int sz) {
        PaletteData pal  = new PaletteData(0xFF0000, 0x00FF00, 0x0000FF);
        ImageData   data = new ImageData(sz, sz, 24, pal);
        data.transparentPixel = pal.getPixel(new RGB(255, 0, 255));

        Image img = new Image(display, data);
        GC    gc  = new GC(img);
        gc.setAntialias(SWT.ON);

        // transparent key colour
        gc.setBackground(new Color(display, 255, 0, 255));
        gc.fillRectangle(0, 0, sz, sz);

        int r = Math.max(2, sz / 8);

        // Background — Noite #1a1814
        gc.setBackground(new Color(display, 26, 24, 20));
        gc.fillRoundRectangle(0, 0, sz, sz, r * 2, r * 2);

        int cx = sz / 2, cy = sz / 2;
        int ringR = sz * 40 / 100;

        // Roda ring — dashed gold (only at ≥24px, too small otherwise)
        if (sz >= 24) {
            gc.setForeground(new Color(display, 232, 184, 75));
            gc.setLineWidth(Math.max(1, sz / 32));
            gc.setLineDash(new int[]{Math.max(2, sz / 12), Math.max(2, sz / 14)});
            gc.drawOval(cx - ringR, cy - ringR, ringR * 2, ringR * 2);
            gc.setLineDash(null);

            // 6 spectator dots around the ring
            gc.setBackground(new Color(display, 232, 184, 75));
            int dotR = Math.max(1, sz / 22);
            for (int i = 0; i < 6; i++) {
                double angle = Math.toRadians(i * 60 - 90);
                int dx = cx + (int)(ringR * Math.cos(angle));
                int dy = cy + (int)(ringR * Math.sin(angle));
                gc.fillOval(dx - dotR, dy - dotR, dotR * 2, dotR * 2);
            }
        }

        // Stick figures — scale off 80-unit design grid
        float u  = sz / 80f;
        int   lw = Math.max(1, sz / 20);
        int   headR = Math.max(2, Math.round(4.5f * u));

        // Player A — gold
        gc.setLineWidth(lw);
        gc.setForeground(new Color(display, 232, 184, 75));
        gc.setBackground(new Color(display, 232, 184, 75));
        gc.fillOval(Math.round(28*u) - headR, Math.round(26*u) - headR, headR*2, headR*2);
        dl(gc, u, 28,31, 32,44);
        dl(gc, u, 30,37, 40,34);
        dl(gc, u, 29,36, 19,32);
        dl(gc, u, 32,44, 26,56);
        dl(gc, u, 26,56, 21,63);
        dl(gc, u, 32,44, 36,56);
        dl(gc, u, 36,56, 32,63);

        // Player B — terracota
        gc.setForeground(new Color(display, 192, 94, 26));
        gc.setBackground(new Color(display, 192, 94, 26));
        gc.fillOval(Math.round(52*u) - headR, Math.round(26*u) - headR, headR*2, headR*2);
        dl(gc, u, 52,31, 48,44);
        dl(gc, u, 50,37, 40,34);
        dl(gc, u, 51,36, 61,32);
        dl(gc, u, 48,44, 54,56);
        dl(gc, u, 54,56, 59,63);
        dl(gc, u, 48,44, 44,56);
        dl(gc, u, 44,56, 48,63);

        gc.dispose();
        return img;
    }

    private static void dl(GC gc, float u, float x1, float y1, float x2, float y2) {
        gc.drawLine(Math.round(x1*u), Math.round(y1*u), Math.round(x2*u), Math.round(y2*u));
    }
}
