package br.com.capoeirassh.ssh.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.widgets.*;

/**
 * Borderless splash screen shown while the main window initialises.
 */
public class SplashScreen {

    private static final int W = 480;
    private static final int H = 280;

    private final Shell splash;

    public SplashScreen(Display display) {
        splash = new Shell(display, SWT.NO_TRIM | SWT.ON_TOP);
        splash.setSize(W, H);
        centerOnScreen(display);

        splash.addPaintListener(e -> paint(e.gc, display));
        splash.open();
        // pump events so the splash renders, then hold for 2 seconds
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (!display.readAndDispatch()) display.sleep();
        }
    }

    public void close() {
        if (!splash.isDisposed()) splash.dispose();
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    private static void paint(GC gc, Display display) {
        Color bg       = new Color(display,   8,  12,  20);
        Color border   = new Color(display,  40, 100, 180);
        Color accent   = new Color(display,  30, 160, 255);
        Color titleClr = new Color(display, 102, 204, 255);
        Color subClr   = new Color(display, 140, 140, 140);
        Color dimClr   = new Color(display,  60,  80, 110);

        try {
            // background
            gc.setBackground(bg);
            gc.fillRectangle(0, 0, W, H);

            // outer border
            gc.setForeground(border);
            gc.setLineWidth(2);
            gc.drawRectangle(1, 1, W - 2, H - 2);

            // top accent line
            gc.setForeground(accent);
            gc.setLineWidth(3);
            gc.drawLine(2, 2, W - 2, 2);

            // ── Terminal icon (large) ────────────────────────────────────────
            drawTerminalIcon(gc, display, 40, 60, 80);

            // ── Text ─────────────────────────────────────────────────────────
            int tx = 148;

            Font titleFont = new Font(display, "Consolas", 26, SWT.BOLD);
            gc.setFont(titleFont);
            gc.setForeground(titleClr);
            gc.drawString("Capoeira", tx, 70, true);
            titleFont.dispose();

            Font subFont = new Font(display, "Consolas", 13, SWT.NORMAL);
            gc.setFont(subFont);
            gc.setForeground(subClr);
            gc.drawString("SSH Client", tx + 4, 106, true);
            subFont.dispose();

            // divider
            gc.setForeground(dimClr);
            gc.setLineWidth(1);
            gc.drawLine(tx, 132, W - 40, 132);

            // feature lines
            Font monoFont = new Font(display, "Consolas", 10, SWT.NORMAL);
            gc.setFont(monoFont);
            gc.setForeground(subClr);
            String[] features = {
                "xterm-256color  ·  SSH2  ·  Multi-session  ·  Encrypted vault"
            };
            int fy = 146;
            for (String f : features) { gc.drawString(f, tx, fy, true); fy += 18; }
            monoFont.dispose();

            // ── Loading indicator ────────────────────────────────────────────
            Font loadFont = new Font(display, "Consolas", 9, SWT.NORMAL);
            gc.setFont(loadFont);
            gc.setForeground(dimClr);
            gc.drawString("Loading...", W - 90, H - 24, true);
            loadFont.dispose();

            // bottom-right version
            Font verFont = new Font(display, "Consolas", 8, SWT.NORMAL);
            gc.setFont(verFont);
            gc.setForeground(dimClr);
            gc.drawString("v1.0", W - 38, H - 24, true);
            verFont.dispose();

        } finally {
            bg.dispose(); border.dispose(); accent.dispose();
            titleClr.dispose(); subClr.dispose(); dimClr.dispose();
        }
    }

    /** Draws the terminal icon at (x,y) scaled to `size` pixels. */
    private static void drawTerminalIcon(GC gc, Display display, int x, int y, int size) {
        Color iconBg    = new Color(display,  20,  60, 120);
        Color iconFg    = new Color(display,  30, 160, 255);
        Color promptClr = new Color(display, 102, 204, 255);
        Color cursorClr = new Color(display,  80, 200, 120);
        try {
            gc.setAntialias(SWT.ON);

            // rounded rect body
            gc.setBackground(iconBg);
            gc.fillRoundRectangle(x, y, size, size, size / 6, size / 6);

            // border
            gc.setForeground(iconFg);
            gc.setLineWidth(2);
            gc.drawRoundRectangle(x, y, size, size, size / 6, size / 6);

            // title bar dots
            int dy = y + size / 8;
            int dotR = Math.max(3, size / 14);
            Color[] dots = {
                new Color(display, 220, 80,  60),
                new Color(display, 220, 180, 40),
                new Color(display, 60,  180, 80)
            };
            int dx = x + size / 6;
            for (Color dot : dots) {
                gc.setBackground(dot);
                gc.fillOval(dx, dy, dotR * 2, dotR * 2);
                dot.dispose();
                dx += dotR * 2 + dotR;
            }

            // separator line
            int sep = y + size / 5;
            gc.setForeground(iconFg);
            gc.setLineWidth(1);
            gc.drawLine(x + 2, sep, x + size - 2, sep);

            // prompt "$ _"
            int py = sep + size / 7;
            Font termFont = new Font(display, "Consolas", Math.max(7, size / 7), SWT.BOLD);
            gc.setFont(termFont);
            gc.setForeground(promptClr);
            gc.drawString("$", x + size / 9, py, true);
            gc.setForeground(cursorClr);
            int curX = x + size / 9 + size / 5;
            gc.fillRectangle(curX, py + 1, Math.max(4, size / 10), Math.max(6, size / 7) - 2);
            termFont.dispose();

        } finally {
            iconBg.dispose(); iconFg.dispose(); promptClr.dispose(); cursorClr.dispose();
        }
    }

    private void centerOnScreen(Display display) {
        Rectangle screen = display.getPrimaryMonitor().getBounds();
        splash.setLocation(
            screen.x + (screen.width  - W) / 2,
            screen.y + (screen.height - H) / 2
        );
    }
}
