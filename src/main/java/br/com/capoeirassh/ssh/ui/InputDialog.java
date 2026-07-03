package br.com.capoeirassh.ssh.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.*;

/** Minimal one-field modal input dialog. */
class InputDialog {
    private final Shell  parent;
    private final String title;
    private final String prompt;
    private String initialValue = "";
    private String result;

    InputDialog(Shell parent, String title, String prompt) {
        this.parent = parent; this.title = title; this.prompt = prompt;
    }

    void setInitialValue(String v) { this.initialValue = v != null ? v : ""; }

    String open() {
        Shell dlg = new Shell(parent, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM);
        dlg.setText(title);
        dlg.setSize(320, 130);
        Rectangle rp = parent.getBounds(); Rectangle rc = dlg.getBounds();
        dlg.setLocation(rp.x + (rp.width - rc.width) / 2, rp.y + (rp.height - rc.height) / 2);

        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 12; gl.marginHeight = 10;
        dlg.setLayout(gl);

        new Label(dlg, SWT.NONE).setText(prompt);
        Text txt = new Text(dlg, SWT.BORDER);
        txt.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        if (!initialValue.isEmpty()) { txt.setText(initialValue); txt.selectAll(); }

        new Label(dlg, SWT.NONE);
        Composite btns = new Composite(dlg, SWT.NONE);
        btns.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
        RowLayout rl = new RowLayout(); rl.spacing = 6;
        btns.setLayout(rl);
        Button ok  = new Button(btns, SWT.PUSH); ok.setText("OK");
        Button can = new Button(btns, SWT.PUSH); can.setText("Cancel");
        dlg.setDefaultButton(ok);

        can.addListener(SWT.Selection, e -> dlg.dispose());
        ok.addListener(SWT.Selection, e -> { result = txt.getText(); dlg.dispose(); });

        dlg.open();
        Display d = parent.getDisplay();
        while (!dlg.isDisposed()) { if (!d.readAndDispatch()) d.sleep(); }
        return result;
    }
}
