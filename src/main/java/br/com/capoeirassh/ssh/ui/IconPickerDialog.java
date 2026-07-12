package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.model.SessionIconType;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;

/**
 * Grid picker for a {@link SessionIconType}. Returns the newly chosen type, {@code null}
 * if the user explicitly picked "(None)" (fall back to the letter avatar), or the
 * original {@code current} value unchanged if the dialog was closed without picking
 * anything (Cancel / window close) — closing is never destructive.
 */
class IconPickerDialog {

    private static final int COLS = 6;

    private final Shell parent;
    private final SessionIconType current;
    private SessionIconType result;

    IconPickerDialog(Shell parent, SessionIconType current) {
        this.parent  = parent;
        this.current = current;
        this.result  = current;   // unchanged unless a tile is actually clicked
    }

    /** @return the chosen type, null for "(None)", or the original value if cancelled. */
    SessionIconType open() {
        Shell dlg = new Shell(parent, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM | SWT.RESIZE);
        dlg.setText("Choose Icon");
        AppIcon.apply(dlg);

        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 10; gl.marginHeight = 10;
        dlg.setLayout(gl);

        ScrolledComposite scrolled = new ScrolledComposite(dlg, SWT.V_SCROLL);
        scrolled.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        scrolled.setExpandHorizontal(true);
        scrolled.setExpandVertical(true);

        Composite grid = new Composite(scrolled, SWT.NONE);
        GridLayout gridGl = new GridLayout(COLS, true);
        gridGl.marginWidth = 4; gridGl.marginHeight = 4;
        gridGl.horizontalSpacing = 4; gridGl.verticalSpacing = 4;
        grid.setLayout(gridGl);

        Display display = dlg.getDisplay();
        Color selBg = new Color(display, 217, 235, 252);
        dlg.addListener(SWT.Dispose, e -> selBg.dispose());

        addTile(grid, display, null, "(None)", selBg, dlg);
        for (SessionIconType t : SessionIconType.values()) addTile(grid, display, t, t.getLabel(), selBg, dlg);

        scrolled.setContent(grid);
        // Let the grid report its own unconstrained natural width (6 tiles, no wrap)
        // and pack() size the shell from that — a hardcoded width guess doesn't
        // account for the scrollbar and native window frame, which clips the last
        // column on some platforms/DPI settings.
        Point gridSize = grid.computeSize(SWT.DEFAULT, SWT.DEFAULT);
        scrolled.setMinSize(gridSize);

        dlg.pack();
        // Cap the height so a tall grid scrolls instead of growing the dialog
        // off-screen; width stays whatever pack() computed.
        Point size = dlg.getSize();
        int maxHeight = 480;
        if (size.y > maxHeight) dlg.setSize(size.x, maxHeight);
        center(dlg, parent);

        dlg.open();
        while (!dlg.isDisposed()) { if (!display.readAndDispatch()) display.sleep(); }
        return result;
    }

    private void addTile(Composite grid, Display display, SessionIconType type, String label,
                          Color selBg, Shell dlg) {
        Composite tile = new Composite(grid, SWT.BORDER);
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, false);
        gd.widthHint = 88; gd.heightHint = 74;
        tile.setLayoutData(gd);
        if (type == current) tile.setBackground(selBg);

        GridLayout tgl = new GridLayout(1, false);
        tgl.marginWidth = 4; tgl.marginHeight = 4; tgl.verticalSpacing = 2;
        tile.setLayout(tgl);

        Label icon = new Label(tile, SWT.CENTER);
        icon.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        icon.setBackground(tile.getBackground());
        if (type != null) icon.setImage(SessionIconRegistry.get(type, 32));

        Label text = new Label(tile, SWT.CENTER | SWT.WRAP);
        text.setText(label);
        text.setBackground(tile.getBackground());
        GridData gdText = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gdText.widthHint = 80;
        text.setLayoutData(gdText);

        Listener pick = e -> {
            result = type;
            dlg.dispose();
        };
        tile.addListener(SWT.MouseUp, pick);
        icon.addListener(SWT.MouseUp, pick);
        text.addListener(SWT.MouseUp, pick);
    }

    private static void center(Shell child, Shell parent) {
        Rectangle rp = parent.getBounds(); Rectangle rc = child.getBounds();
        child.setLocation(rp.x + (rp.width - rc.width) / 2, rp.y + (rp.height - rc.height) / 2);
    }
}
