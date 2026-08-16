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
 *
 * <p>Accepted trade-off: unlike opening the connection itself (which {@code MainWindow} now runs
 * through {@code BusyDialog} — see uploadFiles()/downloadFiles()), every {@code sftp.ls()} call
 * inside this dialog (initial listing, "Up", double-click into a directory) still runs
 * synchronously on the UI thread with no indicator. A slow or malicious/compromised server can
 * still stall a single listing for up to {@code SftpConnection}'s socket-level timeout (15s) per
 * navigation click — bounded, not indefinite, but still a brief freeze. Backgrounding every
 * navigation action here (with its own busy indicator, cancellation, etc.) would be a
 * meaningfully larger change than this dialog's current single-threaded/blocking design and was
 * deliberately left out of this pass.
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
        /** Basename of {@link #path}, split on BOTH '/' and '\' — the remote filename this is
         *  built from (ChannelSftp.LsEntry.getFilename()) comes straight off the SFTP wire with
         *  no validation by the protocol, so a malicious or compromised server can embed a
         *  backslash (a path separator on Windows, this app's default target platform) to smuggle
         *  traversal past a '/'-only split. Same fix, same reasoning, as
         *  BackupBundle.fromProps()'s basename extraction. */
        public String name() {
            int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            String basename = slash >= 0 ? path.substring(slash + 1) : path;
            return sanitizeDisplayName(basename);
        }
    }

    /** Unicode bidirectional-override, embedding, and zero-width characters — the toolkit behind
     *  "Trojan Source"-style display spoofing. A malicious or compromised SFTP server fully
     *  controls {@code ChannelSftp.LsEntry.getFilename()} (no format validation by the protocol),
     *  so it can embed these to make a listed file's name/extension visually different from what
     *  actually gets downloaded/opened. */
    private static final java.util.regex.Pattern DECEPTIVE_CHARS = java.util.regex.Pattern.compile(
        "[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2069\\uFEFF]");

    /** Strips bidi-override/zero-width characters and other non-printable control characters from
     *  a server-supplied name before it's ever shown in the UI or used as a local download
     *  filename — never applied to the actual remote path used for the SFTP transfer itself,
     *  which must stay byte-exact. Package-private (not private) so a JUnit test in this package
     *  can drive it directly. */
    static String sanitizeDisplayName(String rawName) {
        if (rawName == null || rawName.isEmpty()) return "";
        String stripped = DECEPTIVE_CHARS.matcher(rawName).replaceAll("");
        StringBuilder sb = new StringBuilder(stripped.length());
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (!Character.isISOControl(c)) sb.append(c);
        }
        return sb.toString();
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
                // Display only — never the raw name used for the actual sftp.ls()/sftp.get()
                // path, which must stay byte-exact to reach the real remote entry.
                it.setText(new String[]{ sanitizeDisplayName(e.getFilename()) + "/", "" });
                rowEntries.add(e);
            }
            if (mode == Mode.PICK_FILES) {
                for (ChannelSftp.LsEntry e : files) {
                    TableItem it = new TableItem(table, SWT.NONE);
                    it.setText(new String[]{ sanitizeDisplayName(e.getFilename()), humanSize(e.getAttrs().getSize()) });
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
