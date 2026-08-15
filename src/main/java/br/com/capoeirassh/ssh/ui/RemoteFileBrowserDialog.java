package br.com.capoeirassh.ssh.ui;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpException;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Vector;

/**
 * Browses a remote directory tree over a live {@link ChannelSftp}, for the two things the
 * upload/download context-menu actions need: picking a destination folder (upload) or picking
 * one or more files at once (download, multi-selection). Shared by both flows in
 * {@code MainWindow} so there is one navigation implementation, not two.
 */
public class RemoteFileBrowserDialog {

    public enum Mode { PICK_FOLDER, PICK_FILES }

    private final Shell       parent;
    private final ChannelSftp sftp;
    private final Mode        mode;
    private final String      title;

    private String           currentDir;
    private String            selectedFolder; // Mode.PICK_FOLDER result
    private List<PickedFile>  selectedFiles;  // Mode.PICK_FILES result
    private boolean           confirmed = false;

    /** One remote file chosen in {@link Mode#PICK_FILES} — path plus size, so the caller can
     *  size an overall bytes-transferred progress bar without a second round-trip to the server. */
    public static final class PickedFile {
        public final String path;
        public final long   size;
        PickedFile(String path, long size) { this.path = path; this.size = size; }
        public String name() { return path.substring(path.lastIndexOf('/') + 1); }
    }

    public RemoteFileBrowserDialog(Shell parent, ChannelSftp sftp, Mode mode, String title) {
        this.parent = parent;
        this.sftp   = sftp;
        this.mode   = mode;
        this.title  = title;
    }

    /** Returns true if the user confirmed a selection; {@link #getSelectedFolder()} or
     *  {@link #getSelectedFiles()} then holds it, depending on {@link Mode}. */
    public boolean open() {
        Shell dlg = new Shell(parent, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM | SWT.RESIZE);
        dlg.setText(title);
        AppIcon.apply(dlg);
        dlg.setSize(560, 440);
        center(dlg, parent);

        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 14; gl.marginHeight = 12; gl.verticalSpacing = 8;
        dlg.setLayout(gl);

        Composite pathRow = new Composite(dlg, SWT.NONE);
        pathRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout glPath = new GridLayout(2, false);
        glPath.marginWidth = 0; glPath.marginHeight = 0;
        pathRow.setLayout(glPath);
        Button btnUp = new Button(pathRow, SWT.PUSH);
        btnUp.setText("↑ Up");
        Label lblPath = new Label(pathRow, SWT.NONE);
        lblPath.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Table table = new Table(dlg, SWT.BORDER | SWT.FULL_SELECTION
            | (mode == Mode.PICK_FILES ? SWT.MULTI : SWT.SINGLE));
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        String[] cols = { "Name", "Size" };
        int[] widths  = { 400, 90 };
        for (int i = 0; i < cols.length; i++) {
            TableColumn c = new TableColumn(table, SWT.NONE);
            c.setText(cols[i]);
            c.setWidth(widths[i]);
        }

        Composite cmpBtns = new Composite(dlg, SWT.NONE);
        cmpBtns.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
        RowLayout rlBtn = new RowLayout(SWT.HORIZONTAL); rlBtn.spacing = 8;
        cmpBtns.setLayout(rlBtn);
        Button btnOk = new Button(cmpBtns, SWT.PUSH);
        btnOk.setText(mode == Mode.PICK_FOLDER ? "Select This Folder" : "Download");
        Button btnCancel = new Button(cmpBtns, SWT.PUSH);
        btnCancel.setText("Cancel");
        dlg.setDefaultButton(btnOk);

        // Parallel to the table's rows; null marks the synthetic ".." (parent-dir) row.
        List<ChannelSftp.LsEntry> rowEntries = new ArrayList<>();

        Runnable refresh = () -> {
            table.removeAll();
            rowEntries.clear();
            lblPath.setText(currentDir);

            List<ChannelSftp.LsEntry> dirs  = new ArrayList<>();
            List<ChannelSftp.LsEntry> files = new ArrayList<>();
            try {
                @SuppressWarnings("unchecked")
                Vector<ChannelSftp.LsEntry> ls = sftp.ls(currentDir);
                for (ChannelSftp.LsEntry e : ls) {
                    String n = e.getFilename();
                    if (n.equals(".") || n.equals("..")) continue;
                    (e.getAttrs().isDir() ? dirs : files).add(e);
                }
            } catch (SftpException ex) {
                MessageBox mb = new MessageBox(dlg, SWT.ICON_ERROR | SWT.OK);
                mb.setText("Browse");
                mb.setMessage("Could not list " + currentDir + ":\n" + ex.getMessage());
                mb.open();
                return;
            }
            dirs.sort(Comparator.comparing(ChannelSftp.LsEntry::getFilename, String.CASE_INSENSITIVE_ORDER));
            files.sort(Comparator.comparing(ChannelSftp.LsEntry::getFilename, String.CASE_INSENSITIVE_ORDER));

            if (!"/".equals(currentDir)) {
                TableItem up = new TableItem(table, SWT.NONE);
                up.setText(new String[]{ "..", "" });
                rowEntries.add(null);
            }
            for (ChannelSftp.LsEntry e : dirs) {
                TableItem it = new TableItem(table, SWT.NONE);
                it.setText(new String[]{ e.getFilename() + "/", "" });
                rowEntries.add(e);
            }
            if (mode == Mode.PICK_FILES) {
                for (ChannelSftp.LsEntry e : files) {
                    TableItem it = new TableItem(table, SWT.NONE);
                    it.setText(new String[]{ e.getFilename(), humanSize(e.getAttrs().getSize()) });
                    rowEntries.add(e);
                }
            }
        };

        try {
            currentDir = sftp.pwd();
        } catch (SftpException ex) {
            currentDir = "/";
        }
        refresh.run();

        btnUp.addListener(SWT.Selection, e -> {
            currentDir = parentOf(currentDir);
            refresh.run();
        });

        table.addListener(SWT.MouseDoubleClick, e -> {
            int idx = table.getSelectionIndex();
            if (idx < 0 || idx >= rowEntries.size()) return;
            ChannelSftp.LsEntry en = rowEntries.get(idx);
            if (en == null) {
                currentDir = parentOf(currentDir);
                refresh.run();
            } else if (en.getAttrs().isDir()) {
                currentDir = joinPath(currentDir, en.getFilename());
                refresh.run();
            }
        });

        btnCancel.addListener(SWT.Selection, e -> dlg.dispose());
        btnOk.addListener(SWT.Selection, e -> {
            if (mode == Mode.PICK_FOLDER) {
                selectedFolder = currentDir;
                confirmed = true;
                dlg.dispose();
                return;
            }
            List<PickedFile> picked = new ArrayList<>();
            for (int idx : table.getSelectionIndices()) {
                ChannelSftp.LsEntry en = rowEntries.get(idx);
                if (en != null && !en.getAttrs().isDir())
                    picked.add(new PickedFile(joinPath(currentDir, en.getFilename()), en.getAttrs().getSize()));
            }
            if (picked.isEmpty()) {
                MessageBox mb = new MessageBox(dlg, SWT.ICON_WARNING | SWT.OK);
                mb.setText("Download");
                mb.setMessage("Select at least one file.");
                mb.open();
                return;
            }
            selectedFiles = picked;
            confirmed = true;
            dlg.dispose();
        });

        dlg.open();
        Display display = parent.getDisplay();
        while (!dlg.isDisposed()) { if (!display.readAndDispatch()) display.sleep(); }
        return confirmed;
    }

    /** Valid when {@link Mode#PICK_FOLDER} was confirmed. */
    public String getSelectedFolder() { return selectedFolder; }

    /** Valid when {@link Mode#PICK_FILES} was confirmed. */
    public List<PickedFile> getSelectedFiles() { return selectedFiles; }

    /** Package-private (not private) so a JUnit test in this package can drive it directly,
     *  without a Display/SWT dependency — same convention as ImportSessionsDialog.isDuplicate. */
    static String parentOf(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) return "/";
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int idx = p.lastIndexOf('/');
        return idx <= 0 ? "/" : p.substring(0, idx);
    }

    static String joinPath(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    private static void center(Shell child, Shell parent) {
        Rectangle rp = parent.getBounds(); Rectangle rc = child.getBounds();
        child.setLocation(rp.x + (rp.width - rc.width) / 2, rp.y + (rp.height - rc.height) / 2);
    }
}
