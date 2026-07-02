package br.com.quatorzebis.ssh.ui;

import br.com.quatorzebis.ssh.model.CredentialEntry;
import br.com.quatorzebis.ssh.model.SessionInfo;
import br.com.quatorzebis.ssh.storage.CredentialStore;
import br.com.quatorzebis.ssh.storage.SessionStorage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
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

    public SessionDialog(Shell parent, String preselectedGroup) {
        this.parent           = parent;
        this.preselectedGroup = preselectedGroup;
    }

    public void setEditing(SessionInfo s) { this.editing = s; }

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

        // ── Auth radio ────────────────────────────────────────────────────────
        label(dlg, "Authentication:");
        Composite cmpRadio = new Composite(dlg, SWT.NONE);
        cmpRadio.setLayoutData(fill());
        RowLayout rlRadio = new RowLayout(SWT.HORIZONTAL);
        rlRadio.spacing = 10; rlRadio.marginWidth = 0; rlRadio.marginHeight = 0;
        cmpRadio.setLayout(rlRadio);
        Button rbPass = new Button(cmpRadio, SWT.RADIO); rbPass.setText("User / Password");
        Button rbKey  = new Button(cmpRadio, SWT.RADIO); rbKey.setText("Private Key");
        Button rbCred = new Button(cmpRadio, SWT.RADIO); rbCred.setText("Saved Credential");

        // ── PASSWORD rows ─────────────────────────────────────────────────────
        Label lblUser = label(dlg, "Username:");
        Text  txtUser = text(dlg);

        Label lblPwd  = label(dlg, "Password:");
        Text  txtPwd  = PasswordField.create(dlg, fill());
        Composite cmpPwdField = txtPwd.getParent(); // the wrapper composite

        Label lblHint1 = new Label(dlg, SWT.NONE); // col-1 filler
        lblHint1.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        Label lblPwdHint = new Label(dlg, SWT.NONE);
        lblPwdHint.setText("(leave blank to be asked on connect)");
        lblPwdHint.setLayoutData(fill());

        // ── PRIVATE KEY rows ──────────────────────────────────────────────────
        Label lblKeyUser = label(dlg, "Username:");
        Text  txtKeyUser = text(dlg);

        Label lblKeyFile = label(dlg, "Key file:");
        Composite cmpKey = new Composite(dlg, SWT.NONE);
        cmpKey.setLayoutData(fill());
        GridLayout gk = new GridLayout(2, false); gk.marginWidth = 0; gk.marginHeight = 0;
        cmpKey.setLayout(gk);
        Text txtKey = new Text(cmpKey, SWT.BORDER | SWT.READ_ONLY);
        txtKey.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button btnBrowse = new Button(cmpKey, SWT.PUSH); btnBrowse.setText("…");

        // ── SAVED CREDENTIAL rows ─────────────────────────────────────────────
        CredentialStore store = CredentialStore.getInstance();

        Label lblCredPick = label(dlg, "Credential:");
        Combo cmbCred = new Combo(dlg, SWT.DROP_DOWN | SWT.READ_ONLY);
        cmbCred.setLayoutData(fill());

        Label lblHint2 = new Label(dlg, SWT.NONE); // col-1 filler
        lblHint2.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        Button btnNewCred = new Button(dlg, SWT.PUSH);
        btnNewCred.setText("+ New Credential…");
        btnNewCred.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        // populate creds
        cmbCred.add("(select a credential)");
        if (store.isUnlocked()) for (CredentialEntry ce : store.getAll()) cmbCred.add(ce.toString());
        cmbCred.select(0);

        Runnable loadCreds = () -> {
            if (!store.isUnlocked()) {
                if (!new MasterPasswordDialog(dlg).open()) return;
            }
            String prev = cmbCred.getSelectionIndex() > 0 ? cmbCred.getItem(cmbCred.getSelectionIndex()) : null;
            cmbCred.removeAll();
            cmbCred.add("(select a credential)");
            for (CredentialEntry ce : store.getAll()) cmbCred.add(ce.toString());
            cmbCred.select(0);
            if (prev != null) { int i = cmbCred.indexOf(prev); if (i >= 0) cmbCred.select(i); }
        };
        cmbCred.addListener(SWT.FocusIn, e -> { if (!store.isUnlocked()) loadCreds.run(); });
        btnNewCred.addListener(SWT.Selection, e -> { new CredentialManagerDialog(dlg).open(); loadCreds.run(); });

        // ── Logging ───────────────────────────────────────────────────────────
        label(dlg, "Log output:");
        Composite cmpLog = new Composite(dlg, SWT.NONE);
        cmpLog.setLayoutData(fill());
        GridLayout glLog = new GridLayout(3, false);
        glLog.marginWidth = 0; glLog.marginHeight = 0; glLog.horizontalSpacing = 6;
        cmpLog.setLayout(glLog);

        Button chkLog = new Button(cmpLog, SWT.CHECK);
        chkLog.setText("Enable");

        Text txtLogDir = new Text(cmpLog, SWT.BORDER | SWT.READ_ONLY);
        txtLogDir.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        txtLogDir.setToolTipText("Directory where log files will be saved");

        Button btnLogBrowse = new Button(cmpLog, SWT.PUSH);
        btnLogBrowse.setText("…");

        String defaultLogDir = System.getProperty("user.home") + "/.14bis/screen_captures";
        txtLogDir.setText(defaultLogDir);

        chkLog.addListener(SWT.Selection, e -> {
            boolean on = chkLog.getSelection();
            txtLogDir.setEnabled(on);
            btnLogBrowse.setEnabled(on);
        });
        txtLogDir.setEnabled(false);
        btnLogBrowse.setEnabled(false);

        btnLogBrowse.addListener(SWT.Selection, e -> {
            DirectoryDialog dd = new DirectoryDialog(dlg, SWT.NONE);
            dd.setText("Select log directory");
            dd.setFilterPath(txtLogDir.getText());
            String chosen = dd.open();
            if (chosen != null) txtLogDir.setText(chosen);
        });

        label(dlg, "");
        Composite cmpLogFile = new Composite(dlg, SWT.NONE);
        cmpLogFile.setLayoutData(fill());
        GridLayout glLogFile = new GridLayout(2, false);
        glLogFile.marginWidth = 0; glLogFile.marginHeight = 0; glLogFile.horizontalSpacing = 6;
        cmpLogFile.setLayout(glLogFile);
        Label lblLogFile = new Label(cmpLogFile, SWT.NONE);
        lblLogFile.setText("File name:");
        Text txtLogFileName = new Text(cmpLogFile, SWT.BORDER);
        txtLogFileName.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        txtLogFileName.setMessage("e.g. session (timestamp prepended automatically)");
        txtLogFileName.setEnabled(false);
        chkLog.addListener(SWT.Selection, e -> txtLogFileName.setEnabled(chkLog.getSelection()));

        // ── Appearance ────────────────────────────────────────────────────────
        label(dlg, "Appearance:");
        Composite cmpAppear = new Composite(dlg, SWT.NONE);
        cmpAppear.setLayoutData(fill());
        RowLayout rlApp = new RowLayout(SWT.HORIZONTAL);
        rlApp.spacing = 8; rlApp.marginWidth = 0; rlApp.marginHeight = 0; rlApp.center = true;
        cmpAppear.setLayout(rlApp);

        int[] appFontSize = { editing != null ? editing.appearFontSize : 0 };
        String[] appFontName = { editing != null && editing.appearFontName != null ? editing.appearFontName : "" };
        int[] appFg = { editing != null ? editing.appearFgR : 204,
                        editing != null ? editing.appearFgG : 204,
                        editing != null ? editing.appearFgB : 204 };
        int[] appBg = { editing != null ? editing.appearBgR : 0,
                        editing != null ? editing.appearBgG : 0,
                        editing != null ? editing.appearBgB : 0 };

        Label swatchFg = new Label(cmpAppear, SWT.BORDER);
        swatchFg.setLayoutData(new RowData(18, 18));
        Label swatchBg = new Label(cmpAppear, SWT.BORDER);
        swatchBg.setLayoutData(new RowData(18, 18));
        Label lblAppearInfo = new Label(cmpAppear, SWT.NONE);

        Runnable refreshAppear = () -> {
            Display d = dlg.getDisplay();
            Color pf = swatchFg.getBackground();
            swatchFg.setBackground(new Color(d, appFg[0], appFg[1], appFg[2]));
            if (pf != null && !pf.isDisposed() && !pf.equals(d.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND))) pf.dispose();
            Color pb = swatchBg.getBackground();
            swatchBg.setBackground(new Color(d, appBg[0], appBg[1], appBg[2]));
            if (pb != null && !pb.isDisposed() && !pb.equals(d.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND))) pb.dispose();
            lblAppearInfo.setText("  " + (appFontSize[0] > 0 ? appFontSize[0] : 14) + "pt");
            cmpAppear.layout();
        };
        refreshAppear.run();

        Button btnAppear = new Button(cmpAppear, SWT.PUSH);
        btnAppear.setText("Customise…");
        btnAppear.moveAbove(swatchFg);
        btnAppear.addListener(SWT.Selection, e -> {
            int is = appFontSize[0] > 0 ? appFontSize[0] : 14;
            String fn = appFontName[0].isBlank() ? br.com.quatorzebis.ssh.storage.AppearanceSettings.getFontName() : appFontName[0];
            org.eclipse.swt.graphics.RGB if2 = new org.eclipse.swt.graphics.RGB(appFg[0], appFg[1], appFg[2]);
            org.eclipse.swt.graphics.RGB ib  = new org.eclipse.swt.graphics.RGB(appBg[0], appBg[1], appBg[2]);
            TerminalAppearanceDialog tad = new TerminalAppearanceDialog(dlg, fn, is, if2, ib);
            if (tad.open()) {
                appFontName[0] = tad.getChosenFontName();
                appFontSize[0] = tad.getChosenFontSize();
                org.eclipse.swt.graphics.RGB fg2 = tad.getChosenFgColor();
                org.eclipse.swt.graphics.RGB bg2 = tad.getChosenBgColor();
                appFg[0] = fg2.red; appFg[1] = fg2.green; appFg[2] = fg2.blue;
                appBg[0] = bg2.red; appBg[1] = bg2.green; appBg[2] = bg2.blue;
                refreshAppear.run();
            }
        });

        // ── Terminal type / Backspace key ────────────────────────────────────
        label(dlg, "Terminal type:");
        Combo cmbTermType = new Combo(dlg, SWT.DROP_DOWN);
        cmbTermType.setLayoutData(fill());
        cmbTermType.setItems("xterm-256color", "xterm", "vt100", "ansi", "linux");
        cmbTermType.setText(editing != null && editing.terminalType != null && !editing.terminalType.isBlank()
            ? editing.terminalType : "xterm-256color");

        label(dlg, "Backspace key:");
        Combo cmbBackspace = new Combo(dlg, SWT.DROP_DOWN | SWT.READ_ONLY);
        cmbBackspace.setLayoutData(fill());
        cmbBackspace.setItems("DEL (0x7F) — Linux and most systems", "BS (0x08) — AIX");
        cmbBackspace.select((editing != null && editing.backspaceCode == 0x08) ? 1 : 0);

        // ── Buttons ───────────────────────────────────────────────────────────
        new Label(dlg, SWT.NONE);
        Composite cmpBtns = new Composite(dlg, SWT.NONE);
        cmpBtns.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
        RowLayout rlBtn = new RowLayout(SWT.HORIZONTAL); rlBtn.spacing = 8;
        cmpBtns.setLayout(rlBtn);
        Button btnSave   = new Button(cmpBtns, SWT.PUSH); btnSave.setText("Save");
        Button btnCancel = new Button(cmpBtns, SWT.PUSH); btnCancel.setText("Cancel");
        dlg.setDefaultButton(btnSave);

        // ── Show/hide by auth type ─────────────────────────────────────────────
        // Groups: PASSWORD=[lblUser,txtUser,lblPwd,cmpPwdField,lblHint1,lblPwdHint]
        //         KEY    =[lblKeyUser,txtKeyUser,lblKeyFile,cmpKey]
        //         CRED   =[lblCredPick,cmbCred,lblHint2,btnNewCred]
        Control[] passW = { lblUser, txtUser, lblPwd, cmpPwdField, lblHint1, lblPwdHint };
        Control[] keyW  = { lblKeyUser, txtKeyUser, lblKeyFile, cmpKey };
        Control[] credW = { lblCredPick, cmbCred, lblHint2, btnNewCred };

        Runnable updateAuth = () -> {
            boolean p = rbPass.getSelection(), k = rbKey.getSelection(), c = rbCred.getSelection();
            setVisible(passW, p); setVisible(keyW, k); setVisible(credW, c);
            dlg.layout(true, true);
            dlg.pack();
            dlg.setSize(Math.max(dlg.getSize().x, 460), dlg.getSize().y);
            center(dlg, parent);
        };

        rbPass.addListener(SWT.Selection, e -> updateAuth.run());
        rbKey.addListener(SWT.Selection,  e -> updateAuth.run());
        rbCred.addListener(SWT.Selection, e -> updateAuth.run());

        btnBrowse.addListener(SWT.Selection, e -> {
            FileDialog fd = new FileDialog(dlg, SWT.OPEN);
            fd.setText("Select private key");
            String path = fd.open();
            if (path != null) txtKey.setText(path);
        });

        // ── Pre-fill ──────────────────────────────────────────────────────────
        if (editing != null) {
            txtName.setText(editing.name);
            txtHost.setText(editing.host);
            txtPort.setText(String.valueOf(editing.port));
            if (!editing.group.isBlank()) {
                int idx = cmbGroup.indexOf(editing.group);
                if (idx >= 0) cmbGroup.select(idx);
            }
            List<CredentialEntry> cl = store.isUnlocked() ? store.getAll() : List.of();
            switch (editing.authType) {
                case PRIVATE_KEY -> {
                    rbKey.setSelection(true);
                    txtKeyUser.setText(editing.username != null ? editing.username : "");
                    txtKey.setText(editing.keyPath != null ? editing.keyPath : "");
                }
                case SAVED_CREDENTIAL -> {
                    rbCred.setSelection(true);
                    for (int i = 0; i < cl.size(); i++) {
                        if (cl.get(i).id.equals(editing.credentialId)) { cmbCred.select(i + 1); break; }
                    }
                }
                default -> {
                    rbPass.setSelection(true);
                    txtUser.setText(editing.username != null ? editing.username : "");
                }
            }
        } else {
            rbPass.setSelection(true);
            if (preselectedGroup != null && !preselectedGroup.isBlank()) {
                int idx = cmbGroup.indexOf(preselectedGroup);
                if (idx >= 0) cmbGroup.select(idx);
            }
        }
        if (editing != null) {
            chkLog.setSelection(editing.logEnabled);
            if (editing.logDir != null && !editing.logDir.isBlank())
                txtLogDir.setText(editing.logDir);
            if (editing.logFileName != null && !editing.logFileName.isBlank())
                txtLogFileName.setText(editing.logFileName);
            txtLogDir.setEnabled(editing.logEnabled);
            btnLogBrowse.setEnabled(editing.logEnabled);
            txtLogFileName.setEnabled(editing.logEnabled);
        }
        updateAuth.run();
        dlg.pack();
        dlg.setSize(Math.max(dlg.getSize().x, 460), dlg.getSize().y);
        center(dlg, parent);

        // ── Save ──────────────────────────────────────────────────────────────
        btnCancel.addListener(SWT.Selection, e -> dlg.dispose());

        btnSave.addListener(SWT.Selection, e -> {
            String host = txtHost.getText().trim();
            if (host.isEmpty()) { alert(dlg, "Host is required."); return; }

            SessionInfo s = editing != null ? editing : new SessionInfo();
            s.name  = txtName.getText().trim();
            s.host  = host;
            s.port  = parsePort(txtPort.getText());
            int gi  = cmbGroup.getSelectionIndex();
            s.group = gi <= 0 ? "" : cmbGroup.getItem(gi);

            if (rbKey.getSelection()) {
                String user = txtKeyUser.getText().trim();
                if (user.isEmpty()) { alert(dlg, "Username is required."); return; }
                s.authType = SessionInfo.AuthType.PRIVATE_KEY;
                s.username = user; s.keyPath = txtKey.getText().trim(); s.credentialId = "";
            } else if (rbCred.getSelection()) {
                int ci = cmbCred.getSelectionIndex();
                if (ci <= 0) { alert(dlg, "Please select a saved credential."); return; }
                List<CredentialEntry> cur = store.isUnlocked() ? store.getAll() : List.of();
                if (ci - 1 >= cur.size()) { alert(dlg, "Please select a saved credential."); return; }
                s.authType = SessionInfo.AuthType.SAVED_CREDENTIAL;
                s.credentialId = cur.get(ci - 1).id; s.username = ""; s.keyPath = "";
            } else {
                String user = txtUser.getText().trim();
                if (user.isEmpty()) { alert(dlg, "Username is required."); return; }
                s.authType = SessionInfo.AuthType.PASSWORD;
                s.username = user; s.keyPath = ""; s.credentialId = "";
            }

            s.appearFontSize = appFontSize[0];
            s.appearFontName = appFontName[0];
            s.appearFgR = appFg[0]; s.appearFgG = appFg[1]; s.appearFgB = appFg[2];
            s.appearBgR = appBg[0]; s.appearBgG = appBg[1]; s.appearBgB = appBg[2];
            s.logEnabled  = chkLog.getSelection();
            s.logDir      = txtLogDir.getText().trim();
            s.logFileName = txtLogFileName.getText().trim();

            String termType = cmbTermType.getText().trim();
            s.terminalType  = termType.isEmpty() ? "xterm-256color" : termType;
            s.backspaceCode = cmbBackspace.getSelectionIndex() == 1 ? 0x08 : 0x7F;

            try { SessionStorage.save(s); result = s; dlg.dispose(); }
            catch (IOException ex) { alert(dlg, "Failed to save session:\n" + ex.getMessage()); }
        });

        dlg.open();
        Display display = parent.getDisplay();
        while (!dlg.isDisposed()) { if (!display.readAndDispatch()) display.sleep(); }
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void setVisible(Control[] controls, boolean visible) {
        for (Control c : controls) {
            c.setVisible(visible);
            Object ld = c.getLayoutData();
            if (ld instanceof GridData gd) gd.exclude = !visible;
            else { GridData gd = new GridData(SWT.FILL, SWT.CENTER, !(c instanceof Label), false); gd.exclude = !visible; c.setLayoutData(gd); }
        }
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
