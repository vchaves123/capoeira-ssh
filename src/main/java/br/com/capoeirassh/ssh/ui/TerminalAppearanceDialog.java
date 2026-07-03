package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.storage.AppearanceSettings;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

/**
 * Dialog for customising terminal font size and colours.
 * Changes are applied to all open terminals on OK.
 */
public class TerminalAppearanceDialog {

    private final Shell  parent;
    private boolean      confirmed = false;

    private String fontName;
    private int    fontSize;
    private RGB    fgColor;
    private RGB    bgColor;

    public TerminalAppearanceDialog(Shell parent) {
        this(parent, MonoFonts.DEFAULT, 14, new RGB(204, 204, 204), new RGB(0, 0, 0));
    }

    public TerminalAppearanceDialog(Shell parent, String fontName, int fontSize, RGB fg, RGB bg) {
        this.parent   = parent;
        this.fontName = (fontName != null && !fontName.isBlank()) ? fontName : MonoFonts.DEFAULT;
        this.fontSize = fontSize;
        this.fgColor  = fg;
        this.bgColor  = bg;
    }

    /** Returns true if the user pressed OK. */
    public boolean open() {
        Shell dlg = new Shell(parent, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM);
        dlg.setText("Terminal Appearance");
        AppIcon.apply(dlg);
        dlg.setSize(380, 340);
        center(dlg, parent);

        Display display = dlg.getDisplay();

        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth  = 20;
        gl.marginHeight = 16;
        gl.verticalSpacing = 10;
        dlg.setLayout(gl);

        // ── Font family ──────────────────────────────────────────────────────
        Label lblFont = new Label(dlg, SWT.NONE);
        lblFont.setText("Font:");

        java.util.List<String> fontChoices = MonoFonts.available(display);
        if (!fontChoices.contains(fontName)) fontChoices.add(0, fontName);
        Combo fontCombo = new Combo(dlg, SWT.DROP_DOWN | SWT.READ_ONLY);
        fontCombo.setItems(fontChoices.toArray(new String[0]));
        int fontIdx = fontChoices.indexOf(fontName);
        fontCombo.select(fontIdx >= 0 ? fontIdx : 0);
        fontCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // ── Font size ────────────────────────────────────────────────────────
        Label lblSize = new Label(dlg, SWT.NONE);
        lblSize.setText("Font size:");

        Spinner spinner = new Spinner(dlg, SWT.BORDER);
        spinner.setMinimum(6);
        spinner.setMaximum(48);
        spinner.setSelection(fontSize);
        spinner.setLayoutData(new GridData(60, SWT.DEFAULT));

        // ── Text colour ──────────────────────────────────────────────────────
        Label lblFg = new Label(dlg, SWT.NONE);
        lblFg.setText("Text colour:");

        Composite rowFg = colorRow(dlg, display, fgColor);
        Button btnFg = (Button) rowFg.getChildren()[0];
        Label  swFg  = (Label)  rowFg.getChildren()[1];

        // ── Background colour ────────────────────────────────────────────────
        Label lblBg = new Label(dlg, SWT.NONE);
        lblBg.setText("Background colour:");

        Composite rowBg = colorRow(dlg, display, bgColor);
        Button btnBg = (Button) rowBg.getChildren()[0];
        Label  swBg  = (Label)  rowBg.getChildren()[1];

        // ── Preview ──────────────────────────────────────────────────────────
        new Label(dlg, SWT.NONE); // spacer
        Canvas preview = new Canvas(dlg, SWT.BORDER);
        GridData gdPrev = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gdPrev.heightHint = 40;
        preview.setLayoutData(gdPrev);

        Runnable refreshPreview = () -> preview.redraw();

        preview.addPaintListener(pe -> {
            GC gc = pe.gc;
            // background
            Color bg = new Color(display, bgColor);
            gc.setBackground(bg);
            gc.fillRectangle(preview.getClientArea());
            bg.dispose();
            // text
            Color fg = new Color(display, fgColor);
            gc.setForeground(fg);
            Font f = new Font(display, fontName, fontSize, SWT.NORMAL);
            gc.setFont(f);
            gc.drawString("  user@server:~$ ls -la", 4, 8, true);
            f.dispose();
            fg.dispose();
        });

        // colour pickers
        btnFg.addListener(SWT.Selection, e -> {
            RGB chosen = openColorDialog(dlg, fgColor);
            if (chosen != null) {
                fgColor = chosen;
                updateSwatch(display, swFg, fgColor);
                refreshPreview.run();
            }
        });
        btnBg.addListener(SWT.Selection, e -> {
            RGB chosen = openColorDialog(dlg, bgColor);
            if (chosen != null) {
                bgColor = chosen;
                updateSwatch(display, swBg, bgColor);
                refreshPreview.run();
            }
        });
        fontCombo.addListener(SWT.Selection, e -> { fontName = fontCombo.getText(); refreshPreview.run(); });
        spinner.addListener(SWT.Selection, e -> { fontSize = spinner.getSelection(); refreshPreview.run(); });
        spinner.addListener(SWT.Modify,    e -> { fontSize = spinner.getSelection(); refreshPreview.run(); });

        // ── Buttons ──────────────────────────────────────────────────────────
        new Label(dlg, SWT.NONE);
        Composite cmpBtns = new Composite(dlg, SWT.NONE);
        cmpBtns.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
        RowLayout rl = new RowLayout(SWT.HORIZONTAL); rl.spacing = 8;
        cmpBtns.setLayout(rl);
        Button btnReset  = new Button(cmpBtns, SWT.PUSH); btnReset.setText("Reset to defaults");
        Button btnOk     = new Button(cmpBtns, SWT.PUSH); btnOk.setText("OK");
        Button btnCancel = new Button(cmpBtns, SWT.PUSH); btnCancel.setText("Cancel");
        dlg.setDefaultButton(btnOk);

        btnReset.addListener(SWT.Selection, e -> {
            fontName = AppearanceSettings.getFontName();
            fontSize = AppearanceSettings.getFontSize();
            fgColor  = AppearanceSettings.getFgColor();
            bgColor  = AppearanceSettings.getBgColor();
            int idx = fontCombo.indexOf(fontName);
            if (idx < 0) { fontCombo.add(fontName, 0); idx = 0; }
            fontCombo.select(idx);
            spinner.setSelection(fontSize);
            updateSwatch(display, swFg, fgColor);
            updateSwatch(display, swBg, bgColor);
            refreshPreview.run();
        });
        btnOk.addListener(SWT.Selection, e -> {
            fontSize  = spinner.getSelection();
            confirmed = true;
            dlg.dispose();
        });
        btnCancel.addListener(SWT.Selection, e -> dlg.dispose());

        dlg.open();
        while (!dlg.isDisposed()) {
            if (!display.readAndDispatch()) display.sleep();
        }
        return confirmed;
    }

    public String getChosenFontName() { return fontName; }
    public int  getChosenFontSize() { return fontSize; }
    public RGB  getChosenFgColor()  { return fgColor;  }
    public RGB  getChosenBgColor()  { return bgColor;  }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Composite colorRow(Composite parent, Display display, RGB initial) {
        Composite row = new Composite(parent, SWT.NONE);
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        RowLayout rl = new RowLayout(SWT.HORIZONTAL);
        rl.spacing = 8; rl.marginWidth = 0; rl.marginHeight = 0; rl.center = true;
        row.setLayout(rl);

        Button btn = new Button(row, SWT.PUSH);
        btn.setText("Choose…");

        Label swatch = new Label(row, SWT.BORDER);
        RowData rd = new RowData(28, 18);
        swatch.setLayoutData(rd);
        updateSwatch(display, swatch, initial);
        return row;
    }

    private static void updateSwatch(Display display, Label swatch, RGB rgb) {
        Color prev = swatch.getBackground();
        swatch.setBackground(new Color(display, rgb));
        if (prev != null && !prev.isDisposed()
                && !prev.equals(display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND))) {
            prev.dispose();
        }
    }

    private static RGB openColorDialog(Shell parent, RGB initial) {
        ColorDialog cd = new ColorDialog(parent);
        cd.setRGB(initial);
        return cd.open();
    }

    private static void center(Shell child, Shell p) {
        Rectangle rp = p.getBounds(), rc = child.getBounds();
        child.setLocation(rp.x + (rp.width  - rc.width)  / 2,
                          rp.y + (rp.height - rc.height) / 2);
    }
}
