package br.com.quatorzebis.ssh.ui;

import br.com.quatorzebis.ssh.model.SessionInfo;
import br.com.quatorzebis.ssh.storage.SessionImporter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Lets the user scan PuTTY / MobaXterm for saved sessions and pick which ones to import. */
public class ImportSessionsDialog {

    private final Shell parent;
    private Table table;
    private final List<SessionInfo> found  = new ArrayList<>();
    private List<SessionInfo> result;

    public ImportSessionsDialog(Shell parent) { this.parent = parent; }

    public List<SessionInfo> open() {
        Shell dlg = new Shell(parent, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM | SWT.RESIZE);
        dlg.setText("Import Sessions");
        AppIcon.apply(dlg);
        dlg.setSize(560, 420);
        center(dlg, parent);

        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 14; gl.marginHeight = 12; gl.verticalSpacing = 8;
        dlg.setLayout(gl);

        Composite scanRow = new Composite(dlg, SWT.NONE);
        scanRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        RowLayout rl = new RowLayout(SWT.HORIZONTAL);
        rl.spacing = 8; rl.marginWidth = 0; rl.marginHeight = 0;
        scanRow.setLayout(rl);

        Button btnPutty = new Button(scanRow, SWT.PUSH);
        btnPutty.setText("Scan PuTTY Sessions");

        Button btnMoba = new Button(scanRow, SWT.PUSH);
        btnMoba.setText("Browse MobaXterm .ini...");

        Label lblHint = new Label(dlg, SWT.WRAP);
        lblHint.setText("Only host, port, username and name are imported — never passwords, "
            + "even if the source tool stores one. You'll be asked for the password on first connect.");
        lblHint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        table = new Table(dlg, SWT.BORDER | SWT.CHECK | SWT.MULTI | SWT.FULL_SELECTION);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridData gdTable = new GridData(SWT.FILL, SWT.FILL, true, true);
        table.setLayoutData(gdTable);
        String[] cols = { "Name", "Host", "Port", "Username", "Source" };
        int[] widths  = { 160, 150, 60, 100, 130 };
        for (int i = 0; i < cols.length; i++) {
            TableColumn c = new TableColumn(table, SWT.NONE);
            c.setText(cols[i]);
            c.setWidth(widths[i]);
        }

        btnPutty.addListener(SWT.Selection, e -> {
            List<SessionInfo> sessions = SessionImporter.fromPutty();
            if (sessions.isEmpty()) {
                alert(dlg, "No PuTTY sessions found.");
            } else {
                addRows(sessions);
            }
        });

        btnMoba.addListener(SWT.Selection, e -> {
            FileDialog fd = new FileDialog(dlg, SWT.OPEN);
            fd.setText("Select MobaXterm.ini");
            fd.setFilterExtensions(new String[]{ "*.ini", "*.*" });
            String path = fd.open();
            if (path == null) return;
            List<SessionInfo> sessions = SessionImporter.fromMobaXtermIni(Path.of(path));
            if (sessions.isEmpty()) {
                alert(dlg, "No importable SSH sessions found in that file.");
            } else {
                addRows(sessions);
            }
        });

        Composite cmpBtns = new Composite(dlg, SWT.NONE);
        cmpBtns.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
        RowLayout rlBtn = new RowLayout(SWT.HORIZONTAL); rlBtn.spacing = 8;
        cmpBtns.setLayout(rlBtn);
        Button btnImport = new Button(cmpBtns, SWT.PUSH); btnImport.setText("Import Selected");
        Button btnCancel = new Button(cmpBtns, SWT.PUSH); btnCancel.setText("Cancel");
        dlg.setDefaultButton(btnImport);

        btnCancel.addListener(SWT.Selection, e -> dlg.dispose());
        btnImport.addListener(SWT.Selection, e -> {
            List<SessionInfo> selected = new ArrayList<>();
            for (int i = 0; i < table.getItemCount(); i++) {
                if (table.getItem(i).getChecked()) selected.add(found.get(i));
            }
            if (selected.isEmpty()) { alert(dlg, "Select at least one session to import."); return; }
            result = selected;
            dlg.dispose();
        });

        dlg.open();
        Display display = parent.getDisplay();
        while (!dlg.isDisposed()) { if (!display.readAndDispatch()) display.sleep(); }
        return result;
    }

    private void addRows(List<SessionInfo> sessions) {
        for (SessionInfo s : sessions) {
            found.add(s);
            TableItem item = new TableItem(table, SWT.NONE);
            item.setText(new String[]{
                s.name, s.host, String.valueOf(s.port), s.username, s.group
            });
            item.setChecked(true);
        }
    }

    private static void alert(Shell parent, String msg) {
        MessageBox mb = new MessageBox(parent, SWT.ICON_INFORMATION | SWT.OK);
        mb.setMessage(msg);
        mb.open();
    }

    private static void center(Shell child, Shell parent) {
        Rectangle rp = parent.getBounds(); Rectangle rc = child.getBounds();
        child.setLocation(rp.x + (rp.width - rc.width) / 2, rp.y + (rp.height - rc.height) / 2);
    }
}
