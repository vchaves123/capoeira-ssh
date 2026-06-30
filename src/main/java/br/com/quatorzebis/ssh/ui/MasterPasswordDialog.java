package br.com.quatorzebis.ssh.ui;

import br.com.quatorzebis.ssh.storage.CredentialStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

/**
 * Asks for the vault master password.
 *
 * In CREATE mode (vault does not exist yet): shows password + confirm fields.
 * In UNLOCK mode (vault exists): shows only the password field.
 *
 * Returns true if the vault was successfully created/unlocked, false if cancelled.
 */
public class MasterPasswordDialog {

    private final Shell parent;
    private final boolean createMode;

    public MasterPasswordDialog(Shell parent) {
        this.parent     = parent;
        this.createMode = !CredentialStore.getInstance().vaultExists();
    }

    public boolean open() {
        Shell dlg = new Shell(parent, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM);
        dlg.setText(createMode ? "Create Credential Vault" : "Unlock Credential Vault");
        AppIcon.apply(dlg);
        dlg.setSize(400, createMode ? 240 : 200);
        center(dlg);

        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 16; gl.marginHeight = 12; gl.verticalSpacing = 8;
        dlg.setLayout(gl);

        // Header label
        Label header = new Label(dlg, SWT.WRAP);
        header.setText(createMode
            ? "Create a master password to protect your credentials.\nYou will need it every time you start the application."
            : "Enter the master password to unlock your saved credentials.");
        GridData gdHeader = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        header.setLayoutData(gdHeader);

        new Label(dlg, SWT.SEPARATOR | SWT.HORIZONTAL).setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        // Password
        new Label(dlg, SWT.NONE).setText(createMode ? "Master password:" : "Password:");
        Text txtPass = PasswordField.create(dlg, new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Confirm (create mode only)
        Text txtConfirm = null;
        if (createMode) {
            new Label(dlg, SWT.NONE).setText("Confirm:");
            txtConfirm = PasswordField.create(dlg, new GridData(SWT.FILL, SWT.CENTER, true, false));
        }
        final Text confirmField = txtConfirm;

        // Error label
        Label lblError = new Label(dlg, SWT.NONE);
        lblError.setForeground(dlg.getDisplay().getSystemColor(SWT.COLOR_RED));
        lblError.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        // Buttons
        new Label(dlg, SWT.NONE);
        Composite cmpBtns = new Composite(dlg, SWT.NONE);
        cmpBtns.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
        RowLayout rl = new RowLayout(SWT.HORIZONTAL); rl.spacing = 8;
        cmpBtns.setLayout(rl);
        Button btnOk     = new Button(cmpBtns, SWT.PUSH); btnOk.setText(createMode ? "Create" : "Unlock");
        Button btnCancel = new Button(cmpBtns, SWT.PUSH); btnCancel.setText("Cancel");
        dlg.setDefaultButton(btnOk);
        txtPass.setFocus();

        boolean[] result = {false};

        btnCancel.addListener(SWT.Selection, e -> dlg.dispose());

        btnOk.addListener(SWT.Selection, e -> {
            String pw = txtPass.getText();
            if (pw.isEmpty()) { lblError.setText("Password cannot be empty."); return; }

            if (createMode) {
                if (!pw.equals(confirmField.getText())) {
                    lblError.setText("Passwords do not match.");
                    return;
                }
                try {
                    CredentialStore.getInstance().create(pw);
                    result[0] = true;
                    dlg.dispose();
                } catch (Exception ex) {
                    lblError.setText("Error: " + ex.getMessage());
                }
            } else {
                try {
                    CredentialStore.getInstance().unlock(pw);
                    result[0] = true;
                    dlg.dispose();
                } catch (javax.crypto.AEADBadTagException ex) {
                    lblError.setText("Wrong password.");
                } catch (Exception ex) {
                    lblError.setText("Error: " + ex.getMessage());
                }
            }
        });

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
