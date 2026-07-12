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
 * Create / rename / delete session groups. Deleting a group never deletes its sessions —
 * they are moved to the root (Ungrouped) first, matching how dragging a group onto empty
 * space used to work in the old session tree.
 */
class GroupManagerDialog {

    private final Shell parent;
    private Shell dlg;
    private List list;
    /** Group names in the same order as the List widget's items. */
    private java.util.List<String> rowGroups;
    private boolean changed = false;

    GroupManagerDialog(Shell parent) {
        this.parent = parent;
    }

    /** @return true if anything was created/renamed/deleted, so the caller knows to reload. */
    boolean open() {
        dlg = new Shell(parent, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM | SWT.RESIZE);
        dlg.setText("Group Manager");
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

        Button btnNew    = new Button(btns, SWT.PUSH); btnNew.setText("New...");
        Button btnRename = new Button(btns, SWT.PUSH); btnRename.setText("Rename...");
        Button btnDelete = new Button(btns, SWT.PUSH); btnDelete.setText("Delete...");
        Button btnClose  = new Button(btns, SWT.PUSH); btnClose.setText("Close");
        dlg.setDefaultButton(btnClose);

        btnNew.addListener(SWT.Selection, e -> createGroup());
        btnRename.addListener(SWT.Selection, e -> renameSelectedGroup());
        btnDelete.addListener(SWT.Selection, e -> deleteSelectedGroups());
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

    /** Reloads the group list with each name's current session count, e.g. "production (4)". */
    private void refreshList() {
        java.util.List<SessionInfo> sessions = SessionStorage.loadAll();
        Map<String, Integer> counts = new TreeMap<>(String::compareToIgnoreCase);
        java.util.List<String> groups;
        try { groups = SessionStorage.loadGroups(); } catch (Exception ex) { groups = java.util.List.of(); }
        for (String g : groups) counts.put(g, 0);
        for (SessionInfo s : sessions) {
            if (s.group != null && !s.group.isBlank())
                counts.merge(s.group, 1, Integer::sum);
        }

        rowGroups = new java.util.ArrayList<>(counts.keySet());
        list.removeAll();
        for (String g : rowGroups) list.add(g + " (" + counts.get(g) + ")");
        if (rowGroups.isEmpty()) list.add("(no groups yet)");
    }

    /** All currently selected group names (List's own multi-selection). */
    private java.util.List<String> selectedGroups() {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (rowGroups == null) return result;
        for (int idx : list.getSelectionIndices())
            if (idx >= 0 && idx < rowGroups.size()) result.add(rowGroups.get(idx));
        return result;
    }

    private void createGroup() {
        InputDialog input = new InputDialog(dlg, "New Group", "Group name:");
        String name = input.open();
        if (name == null || name.isBlank()) return;
        if (!confirmCollision(name, null)) return;
        try {
            SessionStorage.createGroup(name);
            changed = true;
            refreshList();
        } catch (Exception ex) {
            error("Failed to create group:\n" + ex.getMessage());
        }
    }

    private void renameSelectedGroup() {
        java.util.List<String> sel = selectedGroups();
        if (sel.size() != 1) {
            error(sel.isEmpty() ? "Select a group first." : "Select a single group to rename.");
            return;
        }
        String group = sel.get(0);
        InputDialog input = new InputDialog(dlg, "Rename Group", "New name:");
        input.setInitialValue(group);
        String newName = input.open();
        if (newName == null || newName.isBlank() || newName.equals(group)) return;
        if (!confirmCollision(newName, group)) return;
        try {
            SessionStorage.renameGroup(group, newName);
            changed = true;
            refreshList();
        } catch (Exception ex) {
            error("Failed to rename group:\n" + ex.getMessage());
        }
    }

    /** If candidateName would land on the same on-disk folder as a differently-named existing
     *  group (case-insensitive filesystem, or both collapsing through sanitize() the same way),
     *  ask for confirmation instead of silently merging into it — mirrors deleteGroup()'s own
     *  explicit warn-before-acting pattern. @return true if it's fine to proceed. */
    private boolean confirmCollision(String candidateName, String excludeSelf) {
        String existing = SessionStorage.findCollidingGroup(candidateName, excludeSelf);
        if (existing == null) return true;
        MessageBox mb = new MessageBox(dlg, SWT.ICON_WARNING | SWT.YES | SWT.NO);
        mb.setText("Group Already Exists");
        mb.setMessage("\"" + candidateName + "\" resolves to the same folder as the existing "
            + "group \"" + existing + "\" on this filesystem — they would be merged. Continue?");
        return mb.open() == SWT.YES;
    }

    private void deleteSelectedGroups() {
        java.util.List<String> groups = selectedGroups();
        if (groups.isEmpty()) { error("Select a group first."); return; }

        java.util.List<SessionInfo> allSessions = SessionStorage.loadAll();
        java.util.List<SessionInfo> members = allSessions.stream()
            .filter(s -> groups.contains(s.group)).toList();

        MessageBox mb = new MessageBox(dlg, SWT.ICON_WARNING | SWT.YES | SWT.NO);
        mb.setText("Delete Group" + (groups.size() == 1 ? "" : "s"));
        String groupList = String.join(", ", groups.stream().map(g -> "\"" + g + "\"").toList());
        mb.setMessage((groups.size() == 1
                ? "Delete group " + groupList + "?"
                : "Delete " + groups.size() + " groups (" + groupList + ")?")
            + (members.isEmpty() ? ""
                : "\n\n" + members.size() + " session(s) inside will be moved to Ungrouped, not deleted."));
        if (mb.open() != SWT.YES) return;

        // Move every member out first (same delete-old-file-then-resave pattern as
        // SessionsTab.moveSessionToGroup) so deleteGroup() then finds an empty directory.
        for (SessionInfo s : members) {
            SessionInfo ghost = new SessionInfo();
            ghost.id = s.id; ghost.group = s.group;
            try { SessionStorage.delete(ghost); } catch (Exception ignored) {}
            s.group = "";
            try { SessionStorage.save(s); } catch (Exception ignored) {}
        }
        for (String group : groups) {
            try { SessionStorage.deleteGroup(group); }
            catch (Exception ex) { error("Failed to delete group \"" + group + "\":\n" + ex.getMessage()); }
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
