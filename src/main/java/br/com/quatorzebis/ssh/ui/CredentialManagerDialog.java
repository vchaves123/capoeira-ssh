package br.com.quatorzebis.ssh.ui;

import br.com.quatorzebis.ssh.model.CredentialEntry;
import br.com.quatorzebis.ssh.storage.CredentialStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

import java.util.List;

/**
 * Manage saved credentials (add / edit / delete).
 * Opens the MasterPasswordDialog first if the vault is locked.
 */
public class CredentialManagerDialog {

    private final Shell parent;

    public CredentialManagerDialog(Shell parent) {
        this.parent = parent;
    }

    public void open() {
        CredentialStore store = CredentialStore.getInstance();

        if (!store.isUnlocked()) {
            boolean ok = new MasterPasswordDialog(parent).open();
            if (!ok) return;
        }

        Shell dlg = new Shell(parent, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM | SWT.RESIZE);
        dlg.setText("Credential Manager");
        AppIcon.apply(dlg);
        dlg.setSize(500, 380);
        center(dlg);

        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 12; gl.marginHeight = 10;
        dlg.setLayout(gl);

        // Table
        Table table = new Table(dlg, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridData gdTable = new GridData(SWT.FILL, SWT.FILL, true, true);
        table.setLayoutData(gdTable);

        TableColumn colLabel = new TableColumn(table, SWT.NONE); colLabel.setText("Label");    colLabel.setWidth(160);
        TableColumn colUser  = new TableColumn(table, SWT.NONE); colUser.setText("Username");  colUser.setWidth(160);
        TableColumn colPw    = new TableColumn(table, SWT.NONE); colPw.setText("Password");    colPw.setWidth(100);

        // Button column
        Composite cmpBtns = new Composite(dlg, SWT.NONE);
        cmpBtns.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));
        cmpBtns.setLayout(new GridLayout(1, false));
        Button btnNew    = btn(cmpBtns, "New");
        Button btnEdit   = btn(cmpBtns, "Edit");
        Button btnDelete = btn(cmpBtns, "Delete");

        // Close button row
        new Label(dlg, SWT.NONE);
        Composite cmpClose = new Composite(dlg, SWT.NONE);
        cmpClose.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
        cmpClose.setLayout(new RowLayout(SWT.HORIZONTAL));
        Button btnClose = new Button(cmpClose, SWT.PUSH);
        btnClose.setText("Close");
        dlg.setDefaultButton(btnClose);

        Runnable refresh = () -> {
            table.removeAll();
            for (CredentialEntry e : store.getAll()) {
                TableItem ti = new TableItem(table, SWT.NONE);
                ti.setText(0, e.label.isBlank() ? "(no label)" : e.label);
                ti.setText(1, e.username);
                ti.setText(2, "••••••••");
                ti.setData(e);
            }
        };
        refresh.run();

        btnNew.addListener(SWT.Selection, e -> {
            CredentialEntry entry = editEntry(dlg, null);
            if (entry != null) {
                try { store.addOrUpdate(entry); } catch (Exception ex) { error(dlg, ex.getMessage()); }
                refresh.run();
            }
        });

        btnEdit.addListener(SWT.Selection, e -> {
            int idx = table.getSelectionIndex();
            if (idx < 0) return;
            CredentialEntry existing = (CredentialEntry) table.getItem(idx).getData();
            CredentialEntry updated  = editEntry(dlg, existing);
            if (updated != null) {
                try { store.addOrUpdate(updated); } catch (Exception ex) { error(dlg, ex.getMessage()); }
                refresh.run();
            }
        });

        btnDelete.addListener(SWT.Selection, e -> {
            int idx = table.getSelectionIndex();
            if (idx < 0) return;
            CredentialEntry ce = (CredentialEntry) table.getItem(idx).getData();
            MessageBox mb = new MessageBox(dlg, SWT.ICON_QUESTION | SWT.YES | SWT.NO);
            mb.setText("Delete credential");
            mb.setMessage("Delete credential \"" + ce + "\"?");
            if (mb.open() != SWT.YES) return;
            try { store.delete(ce.id); } catch (Exception ex) { error(dlg, ex.getMessage()); }
            refresh.run();
        });

        btnClose.addListener(SWT.Selection, e -> dlg.dispose());

        dlg.open();
        Display d = parent.getDisplay();
        while (!dlg.isDisposed()) { if (!d.readAndDispatch()) d.sleep(); }
    }

    // -----------------------------------------------------------------------
    // New / Edit entry dialog
    // -----------------------------------------------------------------------
    private CredentialEntry editEntry(Shell owner, CredentialEntry existing) {
        Shell dlg = new Shell(owner, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM);
        dlg.setText(existing == null ? "New Credential" : "Edit Credential");
        AppIcon.apply(dlg);
        dlg.setSize(380, 240);
        center(dlg, owner);

        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 14; gl.marginHeight = 10; gl.verticalSpacing = 8;
        dlg.setLayout(gl);

        lbl(dlg, "Label:");
        Text txtLabel = txt(dlg);
        txtLabel.setMessage("e.g. prod-root");

        lbl(dlg, "Username:");
        Text txtUser = txt(dlg);

        lbl(dlg, "Password:");
        Text txtPass = PasswordField.create(dlg, new GridData(SWT.FILL, SWT.CENTER, true, false));

        if (existing != null) {
            txtLabel.setText(existing.label);
            txtUser.setText(existing.username);
            txtPass.setText(existing.password);
        }

        new Label(dlg, SWT.NONE);
        Composite cmpBtns = new Composite(dlg, SWT.NONE);
        cmpBtns.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
        RowLayout rl = new RowLayout(SWT.HORIZONTAL); rl.spacing = 8;
        cmpBtns.setLayout(rl);
        Button btnSave   = new Button(cmpBtns, SWT.PUSH); btnSave.setText("Save");
        Button btnCancel = new Button(cmpBtns, SWT.PUSH); btnCancel.setText("Cancel");
        dlg.setDefaultButton(btnSave);
        txtLabel.setFocus();

        CredentialEntry[] result = {null};

        btnCancel.addListener(SWT.Selection, e -> dlg.dispose());
        btnSave.addListener(SWT.Selection, e -> {
            String user = txtUser.getText().trim();
            if (user.isEmpty()) { error(dlg, "Username is required."); return; }
            CredentialEntry ce = existing != null ? existing : new CredentialEntry();
            ce.label    = txtLabel.getText().trim();
            ce.username = user;
            ce.password = txtPass.getText();
            result[0]   = ce;
            dlg.dispose();
        });

        dlg.open();
        Display d = owner.getDisplay();
        while (!dlg.isDisposed()) { if (!d.readAndDispatch()) d.sleep(); }
        return result[0];
    }

    // -----------------------------------------------------------------------
    private static Button btn(Composite p, String text) {
        Button b = new Button(p, SWT.PUSH);
        b.setText(text);
        b.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return b;
    }
    private static void lbl(Composite p, String text) { new Label(p, SWT.NONE).setText(text); }
    private static Text txt(Composite p) {
        Text t = new Text(p, SWT.BORDER);
        t.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return t;
    }
    private static void error(Shell parent, String msg) {
        MessageBox mb = new MessageBox(parent, SWT.ICON_ERROR | SWT.OK);
        mb.setText("Error"); mb.setMessage(msg); mb.open();
    }
    private void center(Shell dlg) { center(dlg, parent); }
    private static void center(Shell dlg, Shell owner) {
        Rectangle rp = owner.getBounds(); Rectangle rc = dlg.getBounds();
        dlg.setLocation(rp.x + (rp.width - rc.width) / 2, rp.y + (rp.height - rc.height) / 2);
    }
}
