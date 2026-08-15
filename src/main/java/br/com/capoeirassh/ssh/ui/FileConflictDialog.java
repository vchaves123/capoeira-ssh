package br.com.capoeirassh.ssh.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.*;

/**
 * Asked once per file whose destination already exists, during an upload or download. Must be
 * opened on the UI thread — the transfer runs on a background thread, so the caller marshals
 * this in via {@code Display.syncExec} and blocks on it, same as any other modal prompt.
 */
class FileConflictDialog {

    enum Action { SKIP, RENAME, OVERWRITE }

    /** @param action        what to do with this file
     *  @param applyToAll    if true, the caller should reuse this same action for every
     *                       remaining conflict in the batch without asking again */
    record Result(Action action, boolean applyToAll) {}

    /** Returns null only if the window was closed without a choice (treated as Skip by the caller). */
    Result open(Shell parent, String fileName) {
        Shell dlg = new Shell(parent, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM);
        dlg.setText("File Already Exists");
        AppIcon.apply(dlg);

        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 14; gl.marginHeight = 12; gl.verticalSpacing = 10;
        dlg.setLayout(gl);

        Label lbl = new Label(dlg, SWT.WRAP);
        lbl.setText("\"" + fileName + "\" already exists at the destination. What should be done with it?");
        GridData gdLbl = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gdLbl.widthHint = 340;
        lbl.setLayoutData(gdLbl);

        Button chkApplyAll = new Button(dlg, SWT.CHECK);
        chkApplyAll.setText("Apply this choice to all remaining files");

        Composite cmpBtns = new Composite(dlg, SWT.NONE);
        cmpBtns.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
        RowLayout rlBtn = new RowLayout(SWT.HORIZONTAL); rlBtn.spacing = 8;
        cmpBtns.setLayout(rlBtn);
        Button btnSkip      = new Button(cmpBtns, SWT.PUSH); btnSkip.setText("Skip");
        Button btnRename    = new Button(cmpBtns, SWT.PUSH); btnRename.setText("Rename");
        Button btnOverwrite = new Button(cmpBtns, SWT.PUSH); btnOverwrite.setText("Overwrite");
        dlg.setDefaultButton(btnSkip);

        Result[] result = new Result[1];
        btnSkip.addListener(SWT.Selection, e -> {
            result[0] = new Result(Action.SKIP, chkApplyAll.getSelection());
            dlg.dispose();
        });
        btnRename.addListener(SWT.Selection, e -> {
            result[0] = new Result(Action.RENAME, chkApplyAll.getSelection());
            dlg.dispose();
        });
        btnOverwrite.addListener(SWT.Selection, e -> {
            result[0] = new Result(Action.OVERWRITE, chkApplyAll.getSelection());
            dlg.dispose();
        });

        dlg.pack();
        Rectangle rp = parent.getBounds(), rc = dlg.getBounds();
        dlg.setLocation(rp.x + (rp.width - rc.width) / 2, rp.y + (rp.height - rc.height) / 2);
        dlg.open();

        Display display = parent.getDisplay();
        while (!dlg.isDisposed()) { if (!display.readAndDispatch()) display.sleep(); }
        return result[0];
    }
}
