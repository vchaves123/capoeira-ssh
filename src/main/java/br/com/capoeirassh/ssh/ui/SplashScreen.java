package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.BuildInfo;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.widgets.*;

/**
 * Borderless splash screen shown while the main window initialises.
 * Uses the Capoeira brand palette: Noite background, Ouro/Terracota figures.
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
        // Capoeira palette
        Color bg       = new Color(display,  15,  15,  15);   // Breu     #0f0f0f
        Color surface  = new Color(display,  26,  24,  20);   // Noite    #1a1814
        Color gold     = new Color(display, 232, 184,  75);   // Ouro     #E8B84B
        Color terra    = new Color(display, 192,  94,  26);   // Terracota #C05E1A
        Color areia    = new Color(display, 240, 237, 230);   // Areia    #f0ede6
        Color dimClr   = new Color(display, 106,  98,  88);   // muted warm grey

        try {
            gc.setAntialias(SWT.ON);

            // Full background
            gc.setBackground(bg);
            gc.fillRectangle(0, 0, W, H);

            // Surface panel (left icon zone)
            gc.setBackground(surface);
            gc.fillRoundRectangle(24, 20, 112, 112, 16, 16);

            // Gold border around surface panel
            gc.setForeground(gold);
            gc.setLineWidth(1);
            gc.drawRoundRectangle(24, 20, 112, 112, 16, 16);

            // Top accent bar
            gc.setForeground(gold);
            gc.setLineWidth(3);
            gc.drawLine(2, 2, W - 2, 2);

            // Outer border
            gc.setForeground(dimClr);
            gc.setLineWidth(1);
            gc.drawRectangle(1, 1, W - 2, H - 2);

            // ── Roda icon in the left panel ──────────────────────────────────
            drawRodaIcon(gc, display, 80, 76);

            // ── Text block ───────────────────────────────────────────────────
            int tx = 158;

            Font titleFont = new Font(display, "Consolas", 28, SWT.BOLD);
            gc.setFont(titleFont);
            gc.setForeground(gold);
            gc.drawString("Capoeira", tx, 62, true);
            titleFont.dispose();

            Font subFont = new Font(display, "Consolas", 14, SWT.NORMAL);
            gc.setFont(subFont);
            gc.setForeground(terra);
            gc.drawString("SSH Client", tx + 4, 98, true);
            subFont.dispose();

            // Divider
            gc.setForeground(dimClr);
            gc.setLineWidth(1);
            gc.drawLine(tx, 126, W - 36, 126);

            // Feature line
            Font monoFont = new Font(display, "Consolas", 10, SWT.NORMAL);
            gc.setFont(monoFont);
            gc.setForeground(areia);
            gc.drawString("xterm-256color  ·  SSH2  ·  Multi-session  ·  Encrypted vault",
                    tx, 140, true);
            monoFont.dispose();

            // Tagline
            Font tagFont = new Font(display, "Consolas", 9, SWT.NORMAL);
            gc.setFont(tagFont);
            gc.setForeground(dimClr);
            gc.drawString("The art of secure connection.", tx, 164, true);
            tagFont.dispose();

            // Bottom-left: version
            Font verFont = new Font(display, "Consolas", 8, SWT.NORMAL);
            gc.setFont(verFont);
            gc.setForeground(dimClr);
            String ver = "v" + BuildInfo.VERSION + "  build #" + BuildInfo.BUILD;
            gc.drawString(ver, 28, H - 22, true);
            verFont.dispose();

            // Bottom-right: loading
            Font loadFont = new Font(display, "Consolas", 8, SWT.NORMAL);
            gc.setFont(loadFont);
            gc.setForeground(dimClr);
            gc.drawString("Loading...", W - 78, H - 22, true);
            loadFont.dispose();

        } finally {
            bg.dispose(); surface.dispose(); gold.dispose();
            terra.dispose(); areia.dispose(); dimClr.dispose();
        }
    }

    /**
     * Draws the Roda icon centred at (cx, cy).
     * Two stick figures (gold / terracota) facing each other inside a dashed ring.
     */
    private static void drawRodaIcon(GC gc, Display display, int cx, int cy) {
        int ringR = 40;
        float u   = ringR / 40f;   // design grid: 80 units wide, centre at 40,40

        Color gold  = new Color(display, 232, 184,  75);
        Color terra = new Color(display, 192,  94,  26);
        try {
            // Dashed roda ring
            gc.setForeground(new Color(display, 232, 184, 75));
            gc.setLineWidth(1);
            gc.setLineDash(new int[]{5, 4});
            gc.drawOval(cx - ringR, cy - ringR, ringR * 2, ringR * 2);
            gc.setLineDash(null);

            // 6 spectator dots
            gc.setBackground(new Color(display, 232, 184, 75));
            for (int i = 0; i < 6; i++) {
                double angle = Math.toRadians(i * 60 - 90);
                int dx = cx + (int)(ringR * Math.cos(angle));
                int dy = cy + (int)(ringR * Math.sin(angle));
                gc.fillOval(dx - 3, dy - 3, 6, 6);
            }

            // Player A — gold (left)
            gc.setLineWidth(2);
            gc.setForeground(gold);
            gc.setBackground(gold);
            int hrA = 5;
            gc.fillOval(cx + Math.round((28-40)*u) - hrA, cy + Math.round((26-40)*u) - hrA, hrA*2, hrA*2);
            rLine(gc, cx, cy, u, 28,31, 32,44);
            rLine(gc, cx, cy, u, 30,37, 40,34);
            rLine(gc, cx, cy, u, 29,36, 19,32);
            rLine(gc, cx, cy, u, 32,44, 26,56);
            rLine(gc, cx, cy, u, 26,56, 21,63);
            rLine(gc, cx, cy, u, 32,44, 36,56);
            rLine(gc, cx, cy, u, 36,56, 32,63);

            // Player B — terracota (right)
            gc.setForeground(terra);
            gc.setBackground(terra);
            int hrB = 5;
            gc.fillOval(cx + Math.round((52-40)*u) - hrB, cy + Math.round((26-40)*u) - hrB, hrB*2, hrB*2);
            rLine(gc, cx, cy, u, 52,31, 48,44);
            rLine(gc, cx, cy, u, 50,37, 40,34);
            rLine(gc, cx, cy, u, 51,36, 61,32);
            rLine(gc, cx, cy, u, 48,44, 54,56);
            rLine(gc, cx, cy, u, 54,56, 59,63);
            rLine(gc, cx, cy, u, 48,44, 44,56);
            rLine(gc, cx, cy, u, 44,56, 48,63);

        } finally {
            gold.dispose(); terra.dispose();
        }
    }

    /** Draw a line using design-grid coordinates (0-80 grid, centred at 40,40). */
    private static void rLine(GC gc, int cx, int cy, float u,
                              float x1, float y1, float x2, float y2) {
        gc.drawLine(cx + Math.round((x1-40)*u), cy + Math.round((y1-40)*u),
                    cx + Math.round((x2-40)*u), cy + Math.round((y2-40)*u));
    }

    private void centerOnScreen(Display display) {
        Rectangle screen = display.getPrimaryMonitor().getBounds();
        splash.setLocation(
            screen.x + (screen.width  - W) / 2,
            screen.y + (screen.height - H) / 2
        );
    }
}
