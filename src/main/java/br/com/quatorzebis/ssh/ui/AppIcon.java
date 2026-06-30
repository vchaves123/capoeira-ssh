package br.com.quatorzebis.ssh.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/** Shared application icon builder. */
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
        gc.setTextAntialias(SWT.ON);

        gc.setBackground(new Color(display, 255, 0, 255));
        gc.fillRectangle(0, 0, sz, sz);

        int r   = Math.max(2, sz / 8);
        int pad = Math.max(1, sz / 10);

        gc.setBackground(new Color(display, 22, 27, 34));
        gc.fillRoundRectangle(0, 0, sz, sz, r * 2, r * 2);

        gc.setBackground(new Color(display, 13, 17, 23));
        gc.fillRoundRectangle(pad, pad, sz - pad * 2, sz - pad * 2, r, r);

        gc.setForeground(new Color(display, 0, 200, 160));
        gc.drawLine(pad + r, pad, sz - pad - r, pad);

        Font f = new Font(display, "Consolas", Math.max(6, sz * 5 / 16), SWT.BOLD);
        gc.setFont(f);
        gc.setForeground(new Color(display, 0, 255, 140));
        int tx = pad + Math.max(1, sz / 8);
        int ty = sz / 2 - gc.getFontMetrics().getHeight() / 2 - Math.max(1, sz / 16);
        gc.drawText(">", tx, ty, true);

        int cx = tx + gc.stringExtent(">").x + Math.max(1, sz / 16);
        int cy = ty + gc.getFontMetrics().getHeight() - Math.max(1, sz / 16);
        gc.setForeground(new Color(display, 220, 220, 220));
        gc.setLineWidth(Math.max(1, sz / 16));
        gc.drawLine(cx, cy, cx + Math.max(2, sz / 5), cy);

        int sl = pad + (sz - pad * 2) * 2 / 3;
        gc.setForeground(new Color(display, 255, 255, 255));
        gc.setAlpha(18);
        gc.drawLine(pad + 1, sl, sz - pad - 1, sl);
        gc.setAlpha(255);

        f.dispose();
        gc.dispose();
        return img;
    }
}
