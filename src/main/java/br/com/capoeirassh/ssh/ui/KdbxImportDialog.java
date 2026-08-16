package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.model.CredentialEntry;
import br.com.capoeirassh.ssh.model.SessionInfo;
import br.com.capoeirassh.ssh.storage.KdbxSubprocessClient;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Lets the user pick entries from an external KeePass (.kdbx) file to import as sessions.
 * Never copies a password into this app's vault — each imported {@link CredentialEntry} is a
 * reference (file path + entry UUID) whose password is fetched live from the original file on
 * every connect (see {@link KdbxCredentialResolver}).
 */
public class KdbxImportDialog {

    private final Shell parent;

    public record ImportedItem(CredentialEntry credential, SessionInfo session) {}

    public KdbxImportDialog(Shell parent) { this.parent = parent; }

    /** Returns the pairs to persist, or null if the user cancelled at any point (file picker,
     *  master password prompt, or the entry picker itself). */
    public List<ImportedItem> open() {
        FileDialog fd = new FileDialog(parent, SWT.OPEN);
        fd.setText("Select KeePass File");
        fd.setFilterExtensions(new String[]{ "*.kdbx", "*.*" });
        fd.setFilterNames(new String[]{ "KeePass database (*.kdbx)", "All files (*.*)" });
        String path = fd.open();
        if (path == null) return null;
        Path kdbxFile = Path.of(path);

        char[] master = new KdbxMasterPasswordPromptDialog(parent, kdbxFile).open();
        if (master == null) return null;

        return showPicker(kdbxFile, master);
    }

    private List<ImportedItem> showPicker(Path kdbxFile, char[] master) {
        Shell dlg = new Shell(parent, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM | SWT.RESIZE);
        dlg.setText("Import from KeePass — " + kdbxFile.getFileName());
        AppIcon.apply(dlg);
        dlg.setSize(640, 460);
        center(dlg, parent);
        // The master password char[] is only needed for the one listEntries() call kicked off
        // below — zero it as soon as that call returns, on whichever thread it finishes on.
        dlg.addDisposeListener(e -> Arrays.fill(master, '\0'));

        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 14; gl.marginHeight = 12; gl.verticalSpacing = 8;
        dlg.setLayout(gl);

        Label lblHint = new Label(dlg, SWT.WRAP);
        lblHint.setText("Only host, username, and title are imported — never passwords. Each "
            + "imported session fetches its password live from this file on every connect.");
        lblHint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button chkSaveMaster = new Button(dlg, SWT.CHECK);
        chkSaveMaster.setText("Save this file's master password in the vault (skip being asked again)");
        chkSaveMaster.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        chkSaveMaster.setSelection(false); // opt-in: saving it widens what a vault compromise exposes
        Label lblSaveMasterHint = new Label(dlg, SWT.WRAP);
        lblSaveMasterHint.setText("If checked, the master password below is stored in your vault — "
            + "convenient, but anyone who unlocks the vault can then also open this whole KeePass "
            + "file, not just the entries imported here. Left unchecked, you'll be asked for it "
            + "again each time (cached briefly in memory only).");
        lblSaveMasterHint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Table table = new Table(dlg, SWT.BORDER | SWT.CHECK | SWT.MULTI | SWT.FULL_SELECTION);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setEnabled(false);
        GridData gdTable = new GridData(SWT.FILL, SWT.FILL, true, true);
        table.setLayoutData(gdTable);
        String[] cols = { "Group", "Title", "Username", "URL" };
        int[] widths  = { 140, 160, 140, 160 };
        for (int i = 0; i < cols.length; i++) {
            TableColumn c = new TableColumn(table, SWT.NONE);
            c.setText(cols[i]);
            c.setWidth(widths[i]);
        }

        Label lblLoading = new Label(dlg, SWT.NONE);
        lblLoading.setText("Reading entries…");
        lblLoading.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        Composite selRow = new Composite(dlg, SWT.NONE);
        selRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        RowLayout rlSel = new RowLayout(SWT.HORIZONTAL); rlSel.spacing = 8; rlSel.marginWidth = 0;
        selRow.setLayout(rlSel);
        Button btnAll  = new Button(selRow, SWT.PUSH); btnAll.setText("Select All");
        Button btnNone = new Button(selRow, SWT.PUSH); btnNone.setText("Select None");
        btnAll.setEnabled(false);
        btnNone.setEnabled(false);
        btnAll.addListener(SWT.Selection, e -> setAllChecked(table, true));
        btnNone.addListener(SWT.Selection, e -> setAllChecked(table, false));

        Composite cmpBtns = new Composite(dlg, SWT.NONE);
        cmpBtns.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
        RowLayout rlBtn = new RowLayout(SWT.HORIZONTAL); rlBtn.spacing = 8;
        cmpBtns.setLayout(rlBtn);
        Button btnImport = new Button(cmpBtns, SWT.PUSH); btnImport.setText("Import Selected");
        Button btnCancel = new Button(cmpBtns, SWT.PUSH); btnCancel.setText("Cancel");
        dlg.setDefaultButton(btnImport);
        btnImport.setEnabled(false);

        List<ImportedItem>[] result = new List[]{null};
        List<KdbxSubprocessClient.KdbxEntryInfo>[] loaded = new List[]{null};

        // listEntries() launches a JVM subprocess to open the file — background it so a large
        // database (or a slow/AV-hooked child JVM start) doesn't freeze the window, same pattern
        // as ImportSessionsDialog's PuTTY/MobaXterm scans. The reader reports one entry at a
        // time as it walks the file, so lblLoading's text can show genuine progress rather than
        // a static "please wait" — the listener callback fires on this background thread, so it
        // hops to the UI thread itself before touching the label.
        Display display = dlg.getDisplay();
        new Thread(() -> {
            List<KdbxSubprocessClient.KdbxEntryInfo> entries = null;
            String errorMessage = null;
            try {
                entries = KdbxSubprocessClient.listEntries(kdbxFile, master, count ->
                    display.asyncExec(() -> {
                        if (!dlg.isDisposed()) lblLoading.setText("Reading entries… (" + count + " found)");
                    }));
            } catch (KdbxSubprocessClient.KdbxException ex) {
                errorMessage = ex.getMessage();
            }
            List<KdbxSubprocessClient.KdbxEntryInfo> finalEntries = entries;
            String finalError = errorMessage;
            display.asyncExec(() -> {
                if (dlg.isDisposed()) return;
                lblLoading.setVisible(false);
                ((GridData) lblLoading.getLayoutData()).exclude = true;
                if (finalError != null) {
                    alert(dlg, finalError);
                    dlg.dispose();
                    return;
                }
                if (finalEntries == null || finalEntries.isEmpty()) {
                    alert(dlg, "No entries found in that KeePass file.");
                    dlg.dispose();
                    return;
                }
                loaded[0] = finalEntries;
                table.setEnabled(true);
                btnAll.setEnabled(true);
                btnNone.setEnabled(true);
                btnImport.setEnabled(true);
                for (KdbxSubprocessClient.KdbxEntryInfo info : finalEntries) {
                    TableItem item = new TableItem(table, SWT.NONE);
                    item.setText(new String[]{ info.groupPath(), info.title(), info.username(), info.url() });
                    item.setChecked(false); // unchecked by default — the user picks what to import
                    item.setData(info);
                }
                dlg.layout(true, true);
            });
        }, "kdbx-list-entries").start();

        btnCancel.addListener(SWT.Selection, e -> dlg.dispose());
        btnImport.addListener(SWT.Selection, e -> {
            // Read before dlg.dispose() runs — that's what triggers the dispose listener above
            // that zeroes `master`.
            boolean saveMaster = chkSaveMaster.getSelection();
            List<ImportedItem> selected = new ArrayList<>();
            for (int i = 0; i < table.getItemCount(); i++) {
                if (!table.getItem(i).getChecked()) continue;
                KdbxSubprocessClient.KdbxEntryInfo info =
                        (KdbxSubprocessClient.KdbxEntryInfo) table.getItem(i).getData();
                selected.add(toImportedItem(kdbxFile, info, saveMaster ? master : null));
            }
            if (selected.isEmpty()) { alert(dlg, "Select at least one entry to import."); return; }
            result[0] = selected;
            dlg.dispose();
        });

        dlg.open();
        Display d = parent.getDisplay();
        while (!dlg.isDisposed()) { if (!d.readAndDispatch()) d.sleep(); }
        return result[0];
    }

    /** @param masterToSave the master password to persist into the created CredentialEntry, or
     *                      null to leave it empty (the user opted out of saving it) — see
     *                      KdbxCredentialResolver for how each case is resolved on connect. */
    private static ImportedItem toImportedItem(Path kdbxFile, KdbxSubprocessClient.KdbxEntryInfo info,
                                                char[] masterToSave) {
        CredentialEntry ce = new CredentialEntry();
        ce.label          = info.display();
        ce.username       = info.username();
        ce.kdbxFilePath   = kdbxFile.toAbsolutePath().toString();
        ce.kdbxEntryUuid  = info.uuid();
        ce.password       = masterToSave != null ? Arrays.copyOf(masterToSave, masterToSave.length) : new char[0];

        HostPort hp = parseUrlToHostPort(info.url());

        SessionInfo s = new SessionInfo();
        s.name         = info.display();
        s.host         = hp.host();
        s.port         = hp.port();
        s.username     = info.username();
        s.authType     = SessionInfo.AuthType.SAVED_CREDENTIAL;
        s.credentialId = ce.id;
        s.group        = info.groupPath();

        return new ImportedItem(ce, s);
    }

    // -----------------------------------------------------------------------
    // URL → host/port — best-effort only; a KeePass entry's URL field has no fixed format.
    // -----------------------------------------------------------------------

    record HostPort(String host, int port) {}

    /** Recognizes "scheme://[user@]host[:port][/path]" or a bare "host[:port]" and extracts
     *  host/port from whatever's left after stripping scheme, userinfo, and any trailing
     *  path/query/fragment. Falls back to blank host / port 22 when nothing recognizable is
     *  left — the user fills those in manually after import, same as any other imported session
     *  with an unresolvable source field. Package-private and static so a test can drive it
     *  directly, same convention as ImportSessionsDialog.isDuplicate. */
    static HostPort parseUrlToHostPort(String url) {
        if (url == null) return new HostPort("", 22);
        String s = url.trim();
        if (s.isEmpty()) return new HostPort("", 22);

        int schemeIdx = s.indexOf("://");
        if (schemeIdx >= 0) s = s.substring(schemeIdx + 3);

        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(at + 1);

        for (char c : new char[]{ '/', '?', '#' }) {
            int idx = s.indexOf(c);
            if (idx >= 0) s = s.substring(0, idx);
        }
        s = s.trim();
        if (s.isEmpty()) return new HostPort("", 22);

        // Bracketed IPv6 form, e.g. "[::1]:2222" or "[::1]" — the brackets are exactly what
        // resolve the otherwise-ambiguous "which colon is the port separator" question.
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close > 0) {
                String host = s.substring(1, close);
                String rest = s.substring(close + 1);
                if (rest.startsWith(":") && rest.length() > 1) {
                    String portStr = rest.substring(1);
                    if (portStr.chars().allMatch(Character::isDigit)) {
                        try {
                            int port = Integer.parseInt(portStr);
                            if (port > 0 && port <= 65535) return new HostPort(host, port);
                        } catch (NumberFormatException ignored) { /* fall through */ }
                    }
                }
                return new HostPort(host, 22);
            }
        }

        // A bare (unbracketed) address with more than one colon is an IPv6 literal — splitting
        // on the last colon would silently mistake part of the address for a port, so leave it
        // whole rather than guess.
        if (s.chars().filter(c -> c == ':').count() > 1) return new HostPort(s, 22);

        int colon = s.lastIndexOf(':');
        if (colon > 0 && colon < s.length() - 1) {
            String portStr = s.substring(colon + 1);
            if (!portStr.isEmpty() && portStr.chars().allMatch(Character::isDigit)) {
                try {
                    int port = Integer.parseInt(portStr);
                    if (port > 0 && port <= 65535) return new HostPort(s.substring(0, colon), port);
                } catch (NumberFormatException ignored) { /* fall through */ }
            }
        }
        return new HostPort(s, 22);
    }

    // -----------------------------------------------------------------------
    private static void setAllChecked(Table table, boolean checked) {
        for (TableItem item : table.getItems()) item.setChecked(checked);
    }

    private static void alert(Shell parent, String msg) {
        MessageBox mb = new MessageBox(parent, SWT.ICON_ERROR | SWT.OK);
        mb.setMessage(msg);
        mb.open();
    }

    private static void center(Shell child, Shell parent) {
        Rectangle rp = parent.getBounds(); Rectangle rc = child.getBounds();
        child.setLocation(rp.x + (rp.width - rc.width) / 2, rp.y + (rp.height - rc.height) / 2);
    }
}
