package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.model.CredentialEntry;
import br.com.capoeirassh.ssh.model.SessionInfo;
import br.com.capoeirassh.ssh.storage.CredentialStore;
import br.com.capoeirassh.ssh.storage.SessionStorage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

import java.io.IOException;
import java.util.List;

public class SessionDialog {

    private final Shell  parent;
    private final String preselectedGroup;
    private SessionInfo  editing;
    private SessionInfo  result;
    /** Password/passphrase just typed for manual auth (not saved to the vault) — used
     *  once to pre-fill the first Connect dialog so the user isn't asked to retype it. */
    private char[]        enteredPassword;

    public SessionDialog(Shell parent, String preselectedGroup) {
        this.parent           = parent;
        this.preselectedGroup = preselectedGroup;
    }

    public void setEditing(SessionInfo s) { this.editing = s; }

    /** Non-null only right after {@link #open()} returns a session created/edited with
     *  manual (non-saved) credentials. */
    public char[] getEnteredPassword() { return enteredPassword; }

    public SessionInfo open() {
        Shell dlg = new Shell(parent, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM | SWT.RESIZE);
        dlg.setText(editing == null ? "New Session" : "Edit Session");
        AppIcon.apply(dlg);
        center(dlg, parent);

        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 16; gl.marginHeight = 12; gl.verticalSpacing = 7;
        dlg.setLayout(gl);

        // ── Fixed fields ──────────────────────────────────────────────────────
        label(dlg, "Display name:");
        Text txtName = text(dlg);
        txtName.setMessage("e.g. Production Web");

        label(dlg, "Host:");
        Text txtHost = text(dlg);

        label(dlg, "Port:");
        Text txtPort = text(dlg);
        txtPort.setText("22");

        List<String> groups;
        try { groups = SessionStorage.loadGroups(); } catch (Exception e) { groups = List.of(); }
        label(dlg, "Group:");
        Combo cmbGroup = new Combo(dlg, SWT.DROP_DOWN);
        cmbGroup.setLayoutData(fill());
        cmbGroup.add("(none)");
        for (String g : groups) cmbGroup.add(g);
        cmbGroup.select(0);

        // ── Authentication ───────────────────────────────────────────────────
        CredentialStore store = CredentialStore.getInstance();
        final java.util.concurrent.atomic.AtomicReference<List<CredentialEntry>> credsRef =
            new java.util.concurrent.atomic.AtomicReference<>(List.of());
        // Tracks which saved credential (if any) is currently applied to the fields below.
        final int[]    lockedIdx      = {-1};
        final String[] lockedUsername = {null};

        label(dlg, "Username:");
        Combo cmbUser = new Combo(dlg, SWT.DROP_DOWN);
        cmbUser.setLayoutData(fill());

        new Label(dlg, SWT.NONE); // col-1 filler
        Button chkKey = new Button(dlg, SWT.CHECK);
        chkKey.setText("Use private key");
        chkKey.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        Label lblKeyFile = label(dlg, "Key file:");
        Composite cmpKey = new Composite(dlg, SWT.NONE);
        cmpKey.setLayoutData(fill());
        GridLayout gk = new GridLayout(2, false); gk.marginWidth = 0; gk.marginHeight = 0;
        cmpKey.setLayout(gk);
        Text txtKey = new Text(cmpKey, SWT.BORDER | SWT.READ_ONLY);
        txtKey.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button btnBrowse = new Button(cmpKey, SWT.PUSH); btnBrowse.setText("…");

        Label lblPwd = label(dlg, "Password:");
        Text  txtPwd = PasswordField.create(dlg, fill());

        new Label(dlg, SWT.NONE); // col-1 filler
        Button btnSaveCred = new Button(dlg, SWT.PUSH);
        btnSaveCred.setText("Save Credential…");
        btnSaveCred.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        Runnable reloadCredItems = () -> {
            List<CredentialEntry> creds = store.isUnlocked() ? store.getAll() : List.of();
            credsRef.set(creds);
            String currentText = cmbUser.getText();
            cmbUser.removeAll();
            for (CredentialEntry ce : creds) cmbUser.add(ce.toString());
            cmbUser.setText(currentText);
        };
        // Only prompt to unlock the vault when the user opens the dropdown list
        // (clicks the arrow) — not just when the text field gets focus for typing.
        // The native arrow button scales with display DPI, so derive the zone from it
        // (a fixed 20px band misses the arrow at 150–200% scaling).
        cmbUser.addListener(SWT.MouseDown, e -> {
            int arrowZone = Math.max(20, 20 * dlg.getDisplay().getDPI().x / 96);
            if (e.x < cmbUser.getSize().x - arrowZone) return;
            if (!store.isUnlocked() && new MasterPasswordDialog(dlg).open()) reloadCredItems.run();
        });

        Runnable updateKeyRow = () -> {
            boolean useKey = chkKey.getSelection();
            lblKeyFile.setVisible(useKey);
            cmpKey.setVisible(useKey);
            ((GridData) lblKeyFile.getLayoutData()).exclude = !useKey;
            ((GridData) cmpKey.getLayoutData()).exclude = !useKey;
            lblPwd.setText(useKey ? "Passphrase (optional):" : "Password:");
            dlg.layout(true, true);
            dlg.pack();
            dlg.setSize(Math.max(dlg.getSize().x, 460), dlg.getSize().y);
            center(dlg, parent);
        };

        // Fills the dependent fields from the credential at lockedIdx and grays them out.
        Runnable[] applyLockedCredential = new Runnable[1];
        applyLockedCredential[0] = () -> {
            List<CredentialEntry> creds = credsRef.get();
            if (lockedIdx[0] < 0 || lockedIdx[0] >= creds.size()) return;
            CredentialEntry ce = creds.get(lockedIdx[0]);
            lockedUsername[0] = ce.username;
            cmbUser.setText(ce.username);
            boolean useKey = ce.keyPath != null && !ce.keyPath.isBlank();
            chkKey.setSelection(useKey);
            txtKey.setText(useKey ? ce.keyPath : "");
            txtPwd.setTextChars(ce.password);
            chkKey.setEnabled(false);
            txtPwd.setEditable(false);
            btnBrowse.setEnabled(false);
            updateKeyRow.run();
        };
        Runnable unlockFields = () -> {
            chkKey.setEnabled(true);
            txtPwd.setEditable(true);
            btnBrowse.setEnabled(true);
        };

        cmbUser.addListener(SWT.Selection, e -> {
            int idx = cmbUser.getSelectionIndex();
            List<CredentialEntry> creds = credsRef.get();
            if (idx < 0 || idx >= creds.size()) return;
            lockedIdx[0] = idx;
            applyLockedCredential[0].run();
        });
        cmbUser.addListener(SWT.Modify, e -> {
            if (lockedIdx[0] >= 0 && !cmbUser.getText().equals(lockedUsername[0])) {
                lockedIdx[0] = -1;
                lockedUsername[0] = null;
                unlockFields.run();
            }
        });

        btnBrowse.addListener(SWT.Selection, e -> {
            FileDialog fd = new FileDialog(dlg, SWT.OPEN);
            fd.setText("Select private key");
            String path = fd.open();
            if (path != null) txtKey.setText(path);
        });
        chkKey.addListener(SWT.Selection, e -> updateKeyRow.run());

        btnSaveCred.addListener(SWT.Selection, e -> {
            String user = cmbUser.getText().trim();
            if (user.isEmpty()) { alert(dlg, "Username is required."); return; }
            boolean useKey = chkKey.getSelection();
            if (useKey && txtKey.getText().trim().isEmpty()) { alert(dlg, "Key file is required."); return; }
            if (!store.isUnlocked() && !new MasterPasswordDialog(dlg).open()) return;

            InputDialog labelDlg = new InputDialog(dlg, "Save Credential", "Credential label:");
            labelDlg.setInitialValue(user);
            String label = labelDlg.open();
            if (label == null) return;

            CredentialEntry ce = new CredentialEntry();
            ce.label    = label.trim();
            ce.username = user;
            ce.password = txtPwd.getTextChars();
            ce.keyPath  = useKey ? txtKey.getText().trim() : "";

            try { store.addOrUpdate(ce); }
            catch (Exception ex) { alert(dlg, "Could not save credential:\n" + ex.getMessage()); return; }

            reloadCredItems.run();
            List<CredentialEntry> creds = credsRef.get();
            for (int i = 0; i < creds.size(); i++) {
                if (creds.get(i).id.equals(ce.id)) { lockedIdx[0] = i; break; }
            }
            applyLockedCredential[0].run();
        });

        // ── Configuration (logging / appearance / terminal type / backspace) ───
        final br.com.capoeirassh.ssh.model.ConfigurationSettings[] config = {
            editing != null
                ? br.com.capoeirassh.ssh.model.ConfigurationSettings.fromSession(editing)
                : br.com.capoeirassh.ssh.storage.SessionDefaults.get()
        };

        new Label(dlg, SWT.NONE); // col-1 filler
        Button btnConfig = new Button(dlg, SWT.PUSH);
        btnConfig.setText("Configuration Setting…");
        btnConfig.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        btnConfig.addListener(SWT.Selection, e -> {
            ConfigurationSettingsDialog cfgDlg = new ConfigurationSettingsDialog(
                dlg, "Configuration Setting", config[0], txtHost.getText().trim());
            if (cfgDlg.open()) config[0] = cfgDlg.getResult();
        });

        // ── Buttons ───────────────────────────────────────────────────────────
        new Label(dlg, SWT.NONE);
        Composite cmpBtns = new Composite(dlg, SWT.NONE);
        cmpBtns.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
        RowLayout rlBtn = new RowLayout(SWT.HORIZONTAL); rlBtn.spacing = 8;
        cmpBtns.setLayout(rlBtn);
        Button btnSave   = new Button(cmpBtns, SWT.PUSH); btnSave.setText("Save");
        Button btnCancel = new Button(cmpBtns, SWT.PUSH); btnCancel.setText("Cancel");
        dlg.setDefaultButton(btnSave);

        // ── Pre-fill ──────────────────────────────────────────────────────────
        reloadCredItems.run();
        if (editing != null) {
            txtName.setText(editing.name);
            txtHost.setText(editing.host);
            txtPort.setText(String.valueOf(editing.port));
            if (!editing.group.isBlank()) {
                int idx = cmbGroup.indexOf(editing.group);
                if (idx >= 0) cmbGroup.select(idx);
            }

            boolean linkedToCredential = false;
            if (editing.credentialId != null && !editing.credentialId.isBlank()) {
                List<CredentialEntry> cl = credsRef.get();
                int foundIdx = indexOfCredential(cl, editing.credentialId);
                // Vault likely locked (fresh app start) — try to unlock once so an
                // existing credential link isn't silently dropped if the user saves.
                if (foundIdx < 0 && !store.isUnlocked() && new MasterPasswordDialog(dlg).open()) {
                    reloadCredItems.run();
                    cl = credsRef.get();
                    foundIdx = indexOfCredential(cl, editing.credentialId);
                }
                if (foundIdx >= 0) { lockedIdx[0] = foundIdx; linkedToCredential = true; }
            }
            if (linkedToCredential) {
                applyLockedCredential[0].run();
            } else {
                cmbUser.setText(editing.username != null ? editing.username : "");
                boolean useKey = editing.authType == SessionInfo.AuthType.PRIVATE_KEY;
                chkKey.setSelection(useKey);
                if (useKey) txtKey.setText(editing.keyPath != null ? editing.keyPath : "");
            }
        } else if (preselectedGroup != null && !preselectedGroup.isBlank()) {
            int idx = cmbGroup.indexOf(preselectedGroup);
            if (idx >= 0) cmbGroup.select(idx);
        }
        updateKeyRow.run();
        dlg.pack();
        dlg.setSize(Math.max(dlg.getSize().x, 460), dlg.getSize().y);
        center(dlg, parent);

        // ── Save ──────────────────────────────────────────────────────────────
        btnCancel.addListener(SWT.Selection, e -> dlg.dispose());

        btnSave.addListener(SWT.Selection, e -> {
            String host = txtHost.getText().trim();
            if (host.isEmpty()) { alert(dlg, "Host is required."); return; }

            SessionInfo s = editing != null ? editing : new SessionInfo();
            String oldGroup = editing != null ? editing.group : null;
            s.name  = txtName.getText().trim();
            s.host  = host;
            s.port  = parsePort(txtPort.getText());
            String groupText = cmbGroup.getText().trim();
            s.group = (groupText.isEmpty() || groupText.equals("(none)")) ? "" : groupText;

            String user = cmbUser.getText().trim();

            // Editing a vault-linked session while the vault stayed locked (unlock cancelled, or
            // the credential was deleted): the user could neither see nor change the credential,
            // so preserve the original link instead of silently downgrading to manual auth and
            // wiping credentialId. Guarded to the case where the auth fields still match what the
            // pre-fill restored from the session — any edit to them signals a deliberate switch.
            String  edUser    = editing != null && editing.username != null ? editing.username : "";
            String  edKey     = editing != null && editing.keyPath  != null ? editing.keyPath  : "";
            boolean edKeyMode = editing != null && editing.authType == SessionInfo.AuthType.PRIVATE_KEY;
            boolean authUntouched =
                user.equals(edUser)
                && chkKey.getSelection() == edKeyMode
                && (!chkKey.getSelection() || txtKey.getText().trim().equals(edKey))
                && txtPwd.getCharCount() == 0;
            boolean preserveLink =
                lockedIdx[0] < 0 && editing != null
                && editing.credentialId != null && !editing.credentialId.isBlank()
                && authUntouched;

            if (preserveLink) {
                // s == editing: its authType/username/keyPath/credentialId are already correct.
            } else if (lockedIdx[0] >= 0) {
                List<CredentialEntry> cur = credsRef.get();
                if (lockedIdx[0] >= cur.size()) { alert(dlg, "Please select a saved credential."); return; }
                CredentialEntry ce = cur.get(lockedIdx[0]);
                boolean ceIsKey = ce.keyPath != null && !ce.keyPath.isBlank();
                // A key-based credential must drive PRIVATE_KEY auth (session.keyPath is what
                // SshConnection actually reads); a password credential keeps SAVED_CREDENTIAL,
                // whose username/password are resolved from the vault at connect time.
                s.authType = ceIsKey ? SessionInfo.AuthType.PRIVATE_KEY : SessionInfo.AuthType.SAVED_CREDENTIAL;
                s.username = ceIsKey ? ce.username : "";
                s.keyPath  = ceIsKey ? ce.keyPath  : "";
                s.credentialId = ce.id;
            } else {
                if (user.isEmpty()) { alert(dlg, "Username is required."); return; }
                if (chkKey.getSelection()) {
                    String key = txtKey.getText().trim();
                    if (key.isEmpty()) { alert(dlg, "Key file is required."); return; }
                    s.authType = SessionInfo.AuthType.PRIVATE_KEY;
                    s.keyPath  = key;
                } else {
                    s.authType = SessionInfo.AuthType.PASSWORD;
                    s.keyPath  = "";
                }
                s.username = user;
                s.credentialId = "";
            }

            config[0].applyTo(s);

            try {
                SessionStorage.save(s);
                // Delete old file if group changed during edit
                if (oldGroup != null && !oldGroup.equals(s.group)) {
                    SessionInfo ghost = new SessionInfo();
                    ghost.id    = s.id;
                    ghost.group = oldGroup;
                    try { SessionStorage.delete(ghost); } catch (IOException ignored) {}
                }
                result = s;
                // Capture the just-typed password ONLY on the new-session manual path, and only
                // after a successful save — so a failed/retried save never orphans an un-zeroed
                // copy, and the edit path (which never consumes it) cannot leak it. The caller
                // (openTerminal) zeroes this array after pre-filling the first Connect dialog.
                if (editing == null && lockedIdx[0] < 0) enteredPassword = txtPwd.getTextChars();
                dlg.dispose();
            } catch (IOException ex) {
                alert(dlg, "Failed to save session:\n" + ex.getMessage());
            }
        });

        dlg.open();
        Display display = parent.getDisplay();
        while (!dlg.isDisposed()) { if (!display.readAndDispatch()) display.sleep(); }
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int indexOfCredential(List<CredentialEntry> creds, String id) {
        for (int i = 0; i < creds.size(); i++) if (creds.get(i).id.equals(id)) return i;
        return -1;
    }

    private static Label label(Composite p, String text) {
        Label l = new Label(p, SWT.NONE); l.setText(text);
        l.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        return l;
    }
    private static Text text(Composite p) {
        Text t = new Text(p, SWT.BORDER); t.setLayoutData(fill()); return t;
    }
    private static GridData fill() { return new GridData(SWT.FILL, SWT.CENTER, true, false); }
    private static int parsePort(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 22; }
    }
    private static void alert(Shell parent, String msg) {
        MessageBox mb = new MessageBox(parent, SWT.ICON_WARNING | SWT.OK); mb.setMessage(msg); mb.open();
    }
    private static void center(Shell child, Shell parent) {
        Rectangle rp = parent.getBounds(); Rectangle rc = child.getBounds();
        child.setLocation(rp.x + (rp.width - rc.width) / 2, rp.y + (rp.height - rc.height) / 2);
    }
}
