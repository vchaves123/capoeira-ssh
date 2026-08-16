package br.com.capoeirassh.ssh.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

import java.nio.file.Path;

/**
 * Asks for the master password of an external KeePass (.kdbx) file referenced by an imported
 * credential — see {@link KdbxCredentialResolver}. Unlike {@link MasterPasswordDialog}, this
 * never touches this app's own vault; it's a plain synchronous prompt (the actual file-opening
 * work happens afterwards, in a disposable subprocess — see KdbxSubprocessClient).
 */
public class KdbxMasterPasswordPromptDialog {

    private final Shell parent;
    private final Path  kdbxFile;

    public KdbxMasterPasswordPromptDialog(Shell parent, Path kdbxFile) {
        this.parent   = parent;
        this.kdbxFile = kdbxFile;
    }

    /** Returns the entered password as char[] (caller must zero after use), or null if cancelled. */
    public char[] open() {
        Shell dlg = new Shell(parent, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM);
        dlg.setText("Unlock KeePass File");
        AppIcon.apply(dlg);

        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 16; gl.marginHeight = 12; gl.verticalSpacing = 8;
        dlg.setLayout(gl);

        Label header = new Label(dlg, SWT.WRAP);
        header.setText("Enter the master password for:\n" + kdbxFile.getFileName());
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        new Label(dlg, SWT.SEPARATOR | SWT.HORIZONTAL).setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        new Label(dlg, SWT.NONE).setText("Master password:");
        Text txtPass = PasswordField.create(dlg, new GridData(SWT.FILL, SWT.CENTER, true, false));
        dlg.addDisposeListener(e -> PasswordField.scrub(txtPass));

        new Label(dlg, SWT.NONE);
        Composite cmpBtns = new Composite(dlg, SWT.NONE);
        cmpBtns.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
        RowLayout rl = new RowLayout(SWT.HORIZONTAL); rl.spacing = 8; rl.wrap = false;
        cmpBtns.setLayout(rl);
        Button btnOk     = new Button(cmpBtns, SWT.PUSH); btnOk.setText("Unlock");
        Button btnCancel = new Button(cmpBtns, SWT.PUSH); btnCancel.setText("Cancel");
        dlg.setDefaultButton(btnOk);
        txtPass.setFocus();

        char[][] result = {null};

        btnCancel.addListener(SWT.Selection, e -> dlg.dispose());
        btnOk.addListener(SWT.Selection, e -> {
            result[0] = txtPass.getTextChars();
            dlg.dispose();
        });

        dlg.pack();
        if (dlg.getSize().x < 380) dlg.setSize(380, dlg.getSize().y);
        center(dlg);

        dlg.open();
        Display d = parent.getDisplay();
        while (!dlg.isDisposed()) { if (!d.readAndDispatch()) d.sleep(); }
        return result[0];
    }

    private void center(Shell dlg) {
        Rectangle rp = parent.getBounds();
        Rectangle rc = dlg.getBounds();
        dlg.setLocation(rp.x + (rp.width - rc.width) / 2, rp.y + (rp.height - rc.height) / 2);
    }
}
