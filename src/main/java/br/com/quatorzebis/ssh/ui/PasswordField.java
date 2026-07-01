package br.com.quatorzebis.ssh.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

/**
 * Password Text widget with a hold-to-reveal eye button.
 * Usage: Text txt = PasswordField.create(parent, layoutData);
 */
public final class PasswordField {

    private PasswordField() {}

    /**
     * Creates a composite containing a password Text and an eye button.
     * The composite uses the supplied layoutData.
     * Returns the inner Text widget.
     */
    public static Text create(Composite parent, Object layoutData) {
        Composite cmp = new Composite(parent, SWT.NONE);
        if (layoutData != null) cmp.setLayoutData(layoutData);
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 0; gl.marginHeight = 0; gl.horizontalSpacing = 2;
        cmp.setLayout(gl);

        Text txt = new Text(cmp, SWT.BORDER | SWT.PASSWORD);
        txt.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Display display = parent.getDisplay();
        Image eyeShow = buildEye(display, true);
        Image eyeHide = buildEye(display, false);

        Button eye = new Button(cmp, SWT.FLAT);
        eye.setImage(eyeShow);
        eye.setToolTipText("Hold to show password");
        GridData gd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        gd.widthHint = 22; gd.heightHint = 22;
        eye.setLayoutData(gd);
        eye.addDisposeListener(e -> { eyeShow.dispose(); eyeHide.dispose(); });

        char[] echo = { txt.getEchoChar() };
        eye.addListener(SWT.MouseDown, e -> { txt.setEchoChar((char) 0); eye.setImage(eyeHide); });
        eye.addListener(SWT.MouseUp,   e -> { txt.setEchoChar(echo[0]);  eye.setImage(eyeShow); });

        return txt;
    }

    // ── Eye icon drawing ─────────────────────────────────────────────────────

    private static Image buildEye(Display display, boolean open) {
        int sz = 16;
        PaletteData pal = new PaletteData(0xFF0000, 0x00FF00, 0x0000FF);
        ImageData data  = new ImageData(sz, sz, 24, pal);
        data.transparentPixel = pal.getPixel(new RGB(254, 0, 254));

        Image img = new Image(display, data);
        GC gc = new GC(img);
        gc.setAntialias(SWT.ON);

        // transparent background
        gc.setBackground(new Color(display, 254, 0, 254));
        gc.fillRectangle(0, 0, sz, sz);

        Color ink = new Color(display, 80, 80, 80);
        gc.setForeground(ink);
        gc.setBackground(ink);
        gc.setLineWidth(1);

        int cx = sz / 2, cy = sz / 2;

        if (open) {
            // eye outline: lens shape approximated with two arcs
            gc.drawArc(1, cy - 4, sz - 2, 8, 0, 180);
            gc.drawArc(1, cy - 4, sz - 2, 8, 180, 180);
            // pupil
            gc.fillOval(cx - 2, cy - 2, 4, 4);
        } else {
            // closed eye: just a horizontal curve + strike-through line
            gc.drawArc(1, cy - 3, sz - 2, 6, 0, 180);
            gc.setLineWidth(1);
            gc.drawLine(3, cy + 2, sz - 3, cy + 2);
            // lashes
            gc.drawLine(4,  cy + 2, 3,  cy + 5);
            gc.drawLine(8,  cy + 3, 8,  cy + 6);
            gc.drawLine(12, cy + 2, 13, cy + 5);
        }

        ink.dispose();
        gc.dispose();
        return img;
    }
}
