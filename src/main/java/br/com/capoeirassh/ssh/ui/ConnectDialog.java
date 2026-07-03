package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.model.CredentialEntry;
import br.com.capoeirassh.ssh.model.SessionInfo;
import br.com.capoeirassh.ssh.storage.CredentialStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

import java.util.List;

/**
 * Dialog shown before opening a connection.
 *
 * If the session has a saved credentialId the vault is unlocked automatically
 * and the dialog is skipped — the password is returned directly.
 * Otherwise the user can pick a saved credential or enter credentials manually.
 */
public class ConnectDialog {

    private static final String MANUAL = "-- Enter manually --";

    private final Shell       parent;
    private final SessionInfo session;
    private final char[]      prefillPassword; // used once, only when the manual dialog is shown
    private char[]            result;  // null = cancelled

    public ConnectDialog(Shell parent, SessionInfo session) {
        this(parent, session, null);
    }

    /**
     * @param prefillPassword password/passphrase just typed in the New Session dialog
     *                        (manual auth, not saved to the vault). Pre-fills the field so the
     *                        user isn't asked to retype it on the very first connect. Never
     *                        persisted — only used for this one dialog instance.
     */
    public ConnectDialog(Shell parent, SessionInfo session, char[] prefillPassword) {
        this.parent          = parent;
        this.session         = session;
        this.prefillPassword = prefillPassword;
    }

    /**
     * Returns the password/passphrase to use as a char[] (caller must zero after use),
     * or null if the user cancelled.
     * May return without showing a dialog if the session has a saved credential.
     */
    public char[] open() {
        CredentialStore store = CredentialStore.getInstance();

        // ── Saved credential auth type — auto-connect from vault ─────────────
        if (session.authType == SessionInfo.AuthType.SAVED_CREDENTIAL
                || (session.credentialId != null && !session.credentialId.isBlank())) {
            if (!store.isUnlocked()) {
                boolean ok = new MasterPasswordDialog(parent).open();
                if (!ok) return null;
            }
            CredentialEntry ce = store.findById(session.credentialId);
            if (ce != null) {
                session.username = ce.username;
                return java.util.Arrays.copyOf(ce.password, ce.password.length);
            }
            // Credential was deleted from vault — fall through to manual dialog
        }

        // ── Manual / credential picker dialog ─────────────────────────────
        return showDialog(store);
    }

    private char[] showDialog(CredentialStore store) {
        Shell dlg = new Shell(parent, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM);
        dlg.setText("Connect — " + session.label());
        AppIcon.apply(dlg);
        dlg.setSize(400, 290);
        center(dlg, parent);

        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 16; gl.marginHeight = 12; gl.verticalSpacing = 8;
        dlg.setLayout(gl);

        info(dlg, "Host:", session.host + " : " + session.port);

        boolean keyAuth = session.authType == SessionInfo.AuthType.PRIVATE_KEY;
        info(dlg, "Auth:", keyAuth ? "Private key" : "Password");
        if (keyAuth && session.keyPath != null && !session.keyPath.isBlank())
            info(dlg, "Key:", session.keyPath);

        // Credential picker — only unlocks the vault if the user actually opens the
        // dropdown to pick a saved credential; plain manual auth never touches it.
        final List<CredentialEntry>[] credsHolder = new List[]{ List.of() };
        Combo credCombo = null;
        if (store.vaultExists()) {
            new Label(dlg, SWT.NONE).setText("Credential:");
            Combo cc = new Combo(dlg, SWT.DROP_DOWN | SWT.READ_ONLY);
            cc.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            cc.add(MANUAL);
            cc.select(0);
            if (store.isUnlocked()) {
                credsHolder[0] = store.getAll();
                for (CredentialEntry ce : credsHolder[0]) cc.add(ce.toString());
            } else {
                cc.addListener(SWT.MouseDown, e -> {
                    if (store.isUnlocked()) return;
                    if (new MasterPasswordDialog(parent).open()) {
                        credsHolder[0] = store.getAll();
                        for (CredentialEntry ce : credsHolder[0]) cc.add(ce.toString());
                    }
                });
            }
            credCombo = cc;
        }

        // Username
        new Label(dlg, SWT.NONE).setText("Username:");
        Text txtUser = new Text(dlg, SWT.BORDER);
        txtUser.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        txtUser.setText(session.username);

        // Password / passphrase
        new Label(dlg, SWT.NONE).setText(keyAuth ? "Passphrase (optional):" : "Password:");
        Text txtPass = PasswordField.create(dlg, new GridData(SWT.FILL, SWT.CENTER, true, false));
        if (prefillPassword != null) txtPass.setTextChars(prefillPassword);

        // Wire credential picker → auto-fill fields
        final Combo finalCombo = credCombo;
        if (credCombo != null) {
            credCombo.addListener(SWT.Selection, e -> {
                int idx = finalCombo.getSelectionIndex();
                if (idx <= 0) {
                    txtUser.setText(session.username);
                    txtUser.setEditable(true);
                    txtPass.setText("");
                    txtPass.setEditable(true);
                } else {
                    List<CredentialEntry> creds = credsHolder[0];
                    if (idx - 1 < creds.size()) {
                        CredentialEntry ce = creds.get(idx - 1);
                        txtUser.setText(ce.username);
                        txtUser.setEditable(false);
                        txtPass.setTextChars(ce.password);
                        txtPass.setEditable(false);
                    }
                }
            });
        }

        // Buttons
        new Label(dlg, SWT.NONE);
        Composite cmpBtns = new Composite(dlg, SWT.NONE);
        cmpBtns.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
        RowLayout rl = new RowLayout(SWT.HORIZONTAL); rl.spacing = 8;
        cmpBtns.setLayout(rl);
        Button btnConnect = new Button(cmpBtns, SWT.PUSH); btnConnect.setText("Connect");
        Button btnCancel  = new Button(cmpBtns, SWT.PUSH); btnCancel.setText("Cancel");
        dlg.setDefaultButton(btnConnect);
        txtPass.setFocus();

        btnCancel.addListener(SWT.Selection, e -> dlg.dispose());
        btnConnect.addListener(SWT.Selection, e -> {
            String typedUser = txtUser.getText().trim();
            if (!typedUser.isEmpty()) session.username = typedUser;
            result = txtPass.getTextChars();
            dlg.dispose();
        });

        dlg.open();
        Display d = parent.getDisplay();
        while (!dlg.isDisposed()) { if (!d.readAndDispatch()) d.sleep(); }
        return result;
    }

    private static void info(Composite parent, String label, String value) {
        new Label(parent, SWT.NONE).setText(label);
        Label v = new Label(parent, SWT.NONE);
        v.setText(value);
        v.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    private static void center(Shell child, Shell p) {
        Rectangle rp = p.getBounds(); Rectangle rc = child.getBounds();
        child.setLocation(rp.x + (rp.width - rc.width) / 2, rp.y + (rp.height - rc.height) / 2);
    }
}
