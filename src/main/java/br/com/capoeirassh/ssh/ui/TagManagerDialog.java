package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.model.SessionInfo;
import br.com.capoeirassh.ssh.storage.SessionStorage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.*;

import java.util.Map;
import java.util.TreeMap;

/**
 * Rename / delete tags across every session that carries them. Unlike groups, tags have no
 * on-disk directory of their own — they only exist as entries in each session's tag list — so
 * there is no "New" action here: a tag is created simply by typing it into a session's Tags
 * field, and only ever managed from here afterward.
 */
class TagManagerDialog {

    private final Shell parent;
    private Shell dlg;
    private List list;
    /** Tag names in the same order as the List widget's items. */
    private java.util.List<String> rowTags;
    private boolean changed = false;

    TagManagerDialog(Shell parent) {
        this.parent = parent;
    }

    /** @return true if anything was renamed/deleted, so the caller knows to reload. */
    boolean open() {
        dlg = new Shell(parent, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM | SWT.RESIZE);
        dlg.setText("Tag Manager");
        AppIcon.apply(dlg);

        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 12; gl.marginHeight = 12; gl.verticalSpacing = 8;
        dlg.setLayout(gl);

        list = new List(dlg, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL);
        GridData gdList = new GridData(SWT.FILL, SWT.FILL, true, true);
        gdList.widthHint = 320; gdList.heightHint = 220;
        list.setLayoutData(gdList);

        Composite btns = new Composite(dlg, SWT.NONE);
        btns.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
        RowLayout rl = new RowLayout(); rl.spacing = 6;
        btns.setLayout(rl);

        Button btnRename = new Button(btns, SWT.PUSH); btnRename.setText("Rename...");
        Button btnDelete = new Button(btns, SWT.PUSH); btnDelete.setText("Delete...");
        Button btnClose  = new Button(btns, SWT.PUSH); btnClose.setText("Close");
        dlg.setDefaultButton(btnClose);

        btnRename.addListener(SWT.Selection, e -> renameSelectedTag());
        btnDelete.addListener(SWT.Selection, e -> deleteSelectedTags());
        btnClose.addListener(SWT.Selection, e -> dlg.dispose());

        refreshList();

        dlg.pack();
        dlg.setSize(Math.max(dlg.getSize().x, 360), dlg.getSize().y);
        Rectangle rp = parent.getBounds(); Rectangle rc = dlg.getBounds();
        dlg.setLocation(rp.x + (rp.width - rc.width) / 2, rp.y + (rp.height - rc.height) / 2);

        dlg.open();
        Display display = parent.getDisplay();
        while (!dlg.isDisposed()) { if (!display.readAndDispatch()) display.sleep(); }
        return changed;
    }

    /** Reloads the tag list with each tag's current usage count, e.g. "prod (4)". */
    private void refreshList() {
        java.util.List<SessionInfo> sessions = SessionStorage.loadAll();
        Map<String, Integer> counts = new TreeMap<>(String::compareToIgnoreCase);
        for (SessionInfo s : sessions)
            for (String t : s.tags) counts.merge(t, 1, Integer::sum);

        rowTags = new java.util.ArrayList<>(counts.keySet());
        list.removeAll();
        for (String t : rowTags) list.add(t + " (" + counts.get(t) + ")");
        if (rowTags.isEmpty()) list.add("(no tags yet)");
    }

    /** All currently selected tag names (List's own multi-selection). */
    private java.util.List<String> selectedTags() {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (rowTags == null) return result;
        for (int idx : list.getSelectionIndices())
            if (idx >= 0 && idx < rowTags.size()) result.add(rowTags.get(idx));
        return result;
    }

    private void renameSelectedTag() {
        java.util.List<String> sel = selectedTags();
        if (sel.size() != 1) {
            if (sel.size() > 1) error("Select a single tag to rename.");
            return;
        }
        String tag = sel.get(0);
        InputDialog input = new InputDialog(dlg, "Rename Tag", "New name:");
        input.setInitialValue(tag);
        String newName = input.open();
        if (newName == null || newName.isBlank() || newName.equals(tag)) return;

        for (SessionInfo s : SessionStorage.loadAll()) {
            int idx = s.tags.indexOf(tag);
            if (idx < 0) continue;
            // Renaming onto an existing tag merges them — drop the duplicate instead of
            // ending up with the same tag listed twice on one session.
            if (s.tags.contains(newName)) s.tags.remove(idx);
            else s.tags.set(idx, newName);
            try { SessionStorage.save(s); } catch (Exception ex) {
                error("Failed to rename tag on \"" + s.label() + "\":\n" + ex.getMessage());
            }
        }
        changed = true;
        refreshList();
    }

    private void deleteSelectedTags() {
        java.util.List<String> tags = selectedTags();
        if (tags.isEmpty()) return;

        int affected = 0;
        for (SessionInfo s : SessionStorage.loadAll())
            if (s.tags.stream().anyMatch(tags::contains)) affected++;

        MessageBox mb = new MessageBox(dlg, SWT.ICON_WARNING | SWT.YES | SWT.NO);
        mb.setText("Delete Tag" + (tags.size() == 1 ? "" : "s"));
        String tagList = String.join(", ", tags.stream().map(t -> "\"" + t + "\"").toList());
        mb.setMessage((tags.size() == 1 ? "Delete tag " + tagList + "?" : "Delete " + tags.size() + " tags (" + tagList + ")?")
            + (affected == 0 ? "" : "\n\nRemoved from " + affected + " session(s). Sessions themselves are not affected."));
        if (mb.open() != SWT.YES) return;

        for (SessionInfo s : SessionStorage.loadAll()) {
            if (!s.tags.removeAll(tags)) continue;
            try { SessionStorage.save(s); } catch (Exception ex) {
                error("Failed to remove tag on \"" + s.label() + "\":\n" + ex.getMessage());
            }
        }
        changed = true;
        refreshList();
    }

    private void error(String message) {
        MessageBox mb = new MessageBox(dlg, SWT.ICON_ERROR | SWT.OK);
        mb.setMessage(message);
        mb.open();
    }
}
