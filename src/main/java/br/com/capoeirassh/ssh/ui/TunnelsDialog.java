package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.model.TunnelSpec;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Modal editor for a session's saved tunnels (add / edit / remove). Works on a private copy and
 * only returns it on OK, same staging idea as {@link ConfigurationSettingsDialog}: the caller's
 * list is left untouched if the user cancels.
 */
class TunnelsDialog {

    private final Shell            parent;
    private final List<TunnelSpec> working = new ArrayList<>();
    private List<TunnelSpec>       result;

    TunnelsDialog(Shell parent, List<TunnelSpec> current) {
        this.parent = parent;
        for (TunnelSpec t : current) working.add(t.copy());
    }

    /** The edited list, or null if cancelled. */
    List<TunnelSpec> open() {
        Shell dlg = new Shell(parent, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM | SWT.RESIZE);
        dlg.setText("Tunnels");
        AppIcon.apply(dlg);
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 14; gl.marginHeight = 12; gl.verticalSpacing = 8;
        dlg.setLayout(gl);

        Table table = new Table(dlg, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridData gdTable = new GridData(SWT.FILL, SWT.FILL, true, true);
        gdTable.widthHint = 560; gdTable.heightHint = 200;
        table.setLayoutData(gdTable);
        String[] cols = { "Type", "Listen on", "Forward to", "Auto", "Description" };
        int[] widths  = { 70, 150, 170, 45, 140 };
        for (int i = 0; i < cols.length; i++) {
            TableColumn c = new TableColumn(table, SWT.NONE);
            c.setText(cols[i]); c.setWidth(widths[i]);
        }

        Composite side = new Composite(dlg, SWT.NONE);
        side.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));
        GridLayout sg = new GridLayout(1, true);
        sg.marginWidth = 0; sg.marginHeight = 0; sg.verticalSpacing = 6;
        side.setLayout(sg);
        Button btnAdd    = sideButton(side, "Add…");
        Button btnEdit   = sideButton(side, "Edit…");
        Button btnRemove = sideButton(side, "Remove");

        Label note = new Label(dlg, SWT.WRAP);
        GridData gdNote = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        gdNote.widthHint = 560;
        note.setLayoutData(gdNote);
        note.setText("Tunnels run over their own SSH connection and are started from the tab's "
            + "\"Tunnels…\" menu. \"Auto\" tunnels start when the session opens, but only if its "
            + "credentials are saved (no password prompt).");

        Composite btns = new Composite(dlg, SWT.NONE);
        btns.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false, 2, 1));
        RowLayout rl = new RowLayout(); rl.spacing = 8;
        btns.setLayout(rl);
        Button ok  = new Button(btns, SWT.PUSH); ok.setText("OK");
        Button can = new Button(btns, SWT.PUSH); can.setText("Cancel");
        dlg.setDefaultButton(ok);

        Runnable refresh = () -> {
            int sel = table.getSelectionIndex();
            table.removeAll();
            for (TunnelSpec t : working) {
                TableItem it = new TableItem(table, SWT.NONE);
                it.setText(new String[] {
                    t.type == TunnelSpec.Type.LOCAL ? "Local" : "Remote",
                    t.bindHost + ":" + t.bindPort,
                    t.destHost + ":" + t.destPort,
                    t.autoStart ? "✓" : "",
                    t.description == null ? "" : t.description });
            }
            if (sel >= 0 && sel < working.size()) table.select(sel);
            boolean has = table.getSelectionIndex() >= 0;
            btnEdit.setEnabled(has);
            btnRemove.setEnabled(has);
            btnAdd.setEnabled(working.size() < TunnelSpec.MAX_PER_SESSION);
        };

        Runnable add = () -> {
            TunnelSpec t = new TunnelEditDialog(dlg, null).open();
            if (t == null) return;
            List<TunnelSpec> probe = new ArrayList<>(working);
            probe.add(t);
            String err = TunnelSpec.validateAll(probe);
            if (err != null) { warn(dlg, err); return; }
            working.add(t);
            refresh.run();
            table.select(working.size() - 1);
            refresh.run();
        };
        Runnable edit = () -> {
            int i = table.getSelectionIndex();
            if (i < 0) return;
            TunnelSpec t = new TunnelEditDialog(dlg, working.get(i)).open();
            if (t == null) return;
            List<TunnelSpec> probe = new ArrayList<>(working);
            probe.set(i, t);
            String err = TunnelSpec.validateAll(probe);
            if (err != null) { warn(dlg, err); return; }
            working.set(i, t);
            refresh.run();
        };

        btnAdd.addListener(SWT.Selection, e -> add.run());
        btnEdit.addListener(SWT.Selection, e -> edit.run());
        btnRemove.addListener(SWT.Selection, e -> {
            int i = table.getSelectionIndex();
            if (i < 0) return;
            working.remove(i);
            refresh.run();
        });
        table.addListener(SWT.Selection, e -> refresh.run());
        table.addListener(SWT.MouseDoubleClick, e -> edit.run());
        can.addListener(SWT.Selection, e -> dlg.dispose());
        ok.addListener(SWT.Selection, e -> {
            result = new ArrayList<>(working);
            dlg.dispose();
        });

        refresh.run();
        dlg.pack();
        Rectangle rp = parent.getBounds(); Rectangle rc = dlg.getBounds();
        dlg.setLocation(rp.x + (rp.width - rc.width) / 2, rp.y + (rp.height - rc.height) / 2);

        dlg.open();
        Display d = parent.getDisplay();
        while (!dlg.isDisposed()) { if (!d.readAndDispatch()) d.sleep(); }
        return result;
    }

    private static Button sideButton(Composite p, String text) {
        Button b = new Button(p, SWT.PUSH);
        b.setText(text);
        b.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return b;
    }

    private static void warn(Shell owner, String msg) {
        MessageBox mb = new MessageBox(owner, SWT.ICON_WARNING | SWT.OK);
        mb.setMessage(msg);
        mb.open();
    }
}
