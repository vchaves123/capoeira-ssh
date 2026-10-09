package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.model.TunnelSpec;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.*;

/** Modal dialog to create or edit a single {@link TunnelSpec}. Returns null if cancelled. */
class TunnelEditDialog {

    private final Shell      parent;
    private final TunnelSpec initial;
    private TunnelSpec       result;

    /** @param initial the tunnel to edit, or null to create a new one (defaults: local, loopback). */
    TunnelEditDialog(Shell parent, TunnelSpec initial) {
        this.parent  = parent;
        this.initial = initial;
    }

    TunnelSpec open() {
        Shell dlg = new Shell(parent, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM);
        dlg.setText(initial == null ? "New Tunnel" : "Edit Tunnel");
        AppIcon.apply(dlg);

        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 16; gl.marginHeight = 12; gl.verticalSpacing = 8;
        dlg.setLayout(gl);

        new Label(dlg, SWT.NONE).setText("Type:");
        Combo cmbType = new Combo(dlg, SWT.DROP_DOWN | SWT.READ_ONLY);
        cmbType.setItems("Local  (-L)  listen on this PC", "Remote  (-R)  listen on the server");
        cmbType.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label lblBind = new Label(dlg, SWT.NONE);
        Composite cmpBind = row(dlg);
        Text txtBindHost = new Text(cmpBind, SWT.BORDER);
        txtBindHost.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        txtBindHost.setMessage("address");
        new Label(cmpBind, SWT.NONE).setText(":");
        Text txtBindPort = new Text(cmpBind, SWT.BORDER);
        GridData gdBp = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        gdBp.widthHint = 60;
        txtBindPort.setLayoutData(gdBp);
        txtBindPort.setMessage("port");

        Label lblDest = new Label(dlg, SWT.NONE);
        Composite cmpDest = row(dlg);
        Text txtDestHost = new Text(cmpDest, SWT.BORDER);
        txtDestHost.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        txtDestHost.setMessage("host");
        new Label(cmpDest, SWT.NONE).setText(":");
        Text txtDestPort = new Text(cmpDest, SWT.BORDER);
        GridData gdDp = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        gdDp.widthHint = 60;
        txtDestPort.setLayoutData(gdDp);
        txtDestPort.setMessage("port");

        new Label(dlg, SWT.NONE).setText("Description:");
        Text txtDesc = new Text(dlg, SWT.BORDER);
        txtDesc.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        txtDesc.setMessage("optional, e.g. Postgres production");

        new Label(dlg, SWT.NONE);
        Button chkAuto = new Button(dlg, SWT.CHECK);
        chkAuto.setText("Start automatically when the session opens");

        Label hint = new Label(dlg, SWT.WRAP);
        GridData gdHint = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        gdHint.widthHint = 380;
        hint.setLayoutData(gdHint);

        Runnable relabel = () -> {
            boolean local = cmbType.getSelectionIndex() == 0;
            lblBind.setText(local ? "Listen on (this PC):" : "Listen on (server):");
            lblDest.setText(local ? "Forward to (seen by the server):" : "Forward to (seen by this PC):");
            hint.setText(local
                ? "Connections to the listen address on this PC are carried through the SSH server to the destination."
                : "Connections to the listen address on the server are carried back to the destination, resolved from this PC.");
            dlg.layout(true, true);
        };

        TunnelSpec seed = initial != null ? initial : new TunnelSpec();
        cmbType.select(seed.type == TunnelSpec.Type.REMOTE ? 1 : 0);
        txtBindHost.setText(seed.bindHost);
        txtBindPort.setText(seed.bindPort > 0 ? String.valueOf(seed.bindPort) : "");
        txtDestHost.setText(seed.destHost);
        txtDestPort.setText(seed.destPort > 0 ? String.valueOf(seed.destPort) : "");
        txtDesc.setText(seed.description == null ? "" : seed.description);
        chkAuto.setSelection(seed.autoStart);
        cmbType.addListener(SWT.Selection, e -> relabel.run());
        relabel.run();

        new Label(dlg, SWT.NONE);
        Composite btns = new Composite(dlg, SWT.NONE);
        btns.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
        RowLayout rl = new RowLayout(); rl.spacing = 8;
        btns.setLayout(rl);
        Button ok  = new Button(btns, SWT.PUSH); ok.setText("OK");
        Button can = new Button(btns, SWT.PUSH); can.setText("Cancel");
        dlg.setDefaultButton(ok);
        can.addListener(SWT.Selection, e -> dlg.dispose());

        ok.addListener(SWT.Selection, e -> {
            TunnelSpec t = new TunnelSpec();
            t.type        = cmbType.getSelectionIndex() == 1 ? TunnelSpec.Type.REMOTE : TunnelSpec.Type.LOCAL;
            t.bindHost    = txtBindHost.getText().trim();
            t.bindPort    = parsePort(txtBindPort.getText());
            t.destHost    = txtDestHost.getText().trim();
            t.destPort    = parsePort(txtDestPort.getText());
            t.description = txtDesc.getText().trim();
            t.autoStart   = chkAuto.getSelection();
            if (t.type == TunnelSpec.Type.LOCAL && t.bindHost.isEmpty()) t.bindHost = "127.0.0.1";
            String err = t.validate();
            if (err != null) {
                MessageBox mb = new MessageBox(dlg, SWT.ICON_WARNING | SWT.OK);
                mb.setMessage(err);
                mb.open();
                return;
            }
            // Listening on anything but loopback exposes the forward to the whole network (for a
            // local tunnel) — make that a deliberate choice, never a silent default.
            if (t.type == TunnelSpec.Type.LOCAL && !t.isLoopbackBind()) {
                MessageBox mb = new MessageBox(dlg, SWT.ICON_WARNING | SWT.YES | SWT.NO);
                mb.setText("Listen address");
                mb.setMessage("\"" + t.bindHost + "\" is not a loopback address: other machines on your "
                    + "network will be able to use this tunnel.\n\nUse it anyway?");
                if (mb.open() != SWT.YES) return;
            }
            result = t;
            dlg.dispose();
        });

        dlg.pack();
        Point min = dlg.getSize();
        dlg.setSize(Math.max(min.x, 440), min.y);
        Rectangle rp = parent.getBounds(); Rectangle rc = dlg.getBounds();
        dlg.setLocation(rp.x + (rp.width - rc.width) / 2, rp.y + (rp.height - rc.height) / 2);

        dlg.open();
        Display d = parent.getDisplay();
        while (!dlg.isDisposed()) { if (!d.readAndDispatch()) d.sleep(); }
        return result;
    }

    private static Composite row(Composite p) {
        Composite c = new Composite(p, SWT.NONE);
        c.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout g = new GridLayout(3, false);
        g.marginWidth = 0; g.marginHeight = 0; g.horizontalSpacing = 4;
        c.setLayout(g);
        return c;
    }

    private static int parsePort(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
