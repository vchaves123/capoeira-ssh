package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.model.SessionInfo;
import br.com.capoeirassh.ssh.storage.SessionStorage;
import br.com.capoeirassh.ssh.storage.TagRegistry;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.*;

import java.util.Map;
import java.util.TreeMap;

/**
 * Create / rename / recolor / delete tags. Unlike groups, a tag has no on-disk directory of
 * its own — its existence and color live in {@link TagRegistry}; a session only stores which
 * tag NAMES it carries. Renaming/deleting here also updates every session that carries the
 * affected tag(s).
 */
class TagManagerDialog {

    private final Shell parent;
    private Shell dlg;
    private Table table;
    /** Tag names in the same order as the Table's rows. */
    private java.util.List<String> rowTags;
    private boolean changed = false;

    TagManagerDialog(Shell parent) {
        this.parent = parent;
    }

    /** @return true if anything was created/renamed/recolored/deleted, so the caller reloads. */
    boolean open() {
        dlg = new Shell(parent, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM | SWT.RESIZE);
        dlg.setText("Tag Manager");
        AppIcon.apply(dlg);

        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 12; gl.marginHeight = 12; gl.verticalSpacing = 8;
        dlg.setLayout(gl);

        table = new Table(dlg, SWT.MULTI | SWT.BORDER | SWT.FULL_SELECTION);
        GridData gdList = new GridData(SWT.FILL, SWT.FILL, true, true);
        gdList.widthHint = 320; gdList.heightHint = 220;
        table.setLayoutData(gdList);
        TableColumn col = new TableColumn(table, SWT.NONE);
        col.setWidth(300);
        // Swatch Images are owned by this dialog (not the shared TagRegistry), so they're
        // simple to dispose in one place regardless of how many times the table is rebuilt.
        table.addDisposeListener(e -> disposeSwatches());

        Composite btns = new Composite(dlg, SWT.NONE);
        btns.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
        RowLayout rl = new RowLayout(); rl.spacing = 6;
        btns.setLayout(rl);

        Button btnNew    = new Button(btns, SWT.PUSH); btnNew.setText("New...");
        Button btnRename = new Button(btns, SWT.PUSH); btnRename.setText("Rename...");
        Button btnColor  = new Button(btns, SWT.PUSH); btnColor.setText("Color...");
        Button btnDelete = new Button(btns, SWT.PUSH); btnDelete.setText("Delete...");
        Button btnClose  = new Button(btns, SWT.PUSH); btnClose.setText("Close");
        dlg.setDefaultButton(btnClose);

        btnNew.addListener(SWT.Selection, e -> createTag());
        btnRename.addListener(SWT.Selection, e -> renameSelectedTag());
        btnColor.addListener(SWT.Selection, e -> recolorSelectedTag());
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

    /** Reloads the tag list — a color swatch plus name and current usage count, e.g. "prod (4)". */
    private void refreshList() {
        disposeSwatches();
        java.util.List<SessionInfo> sessions = SessionStorage.loadAll();
        Map<String, Integer> counts = new TreeMap<>(String::compareToIgnoreCase);
        for (String t : TagRegistry.getAll()) counts.put(t, 0);
        for (SessionInfo s : sessions)
            for (String t : s.tags) counts.merge(t, 1, Integer::sum);

        rowTags = new java.util.ArrayList<>(counts.keySet());
        table.removeAll();
        Display d = table.getDisplay();
        for (String tag : rowTags) {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setText(tag + " (" + counts.get(tag) + ")");
            item.setImage(swatch(d, TagRegistry.getColor(tag)));
        }
    }

    private final java.util.List<Image> swatches = new java.util.ArrayList<>();

    private Image swatch(Display d, RGB rgb) {
        Image img = new Image(d, 12, 12);
        GC gc = new GC(img);
        org.eclipse.swt.graphics.Color c = new org.eclipse.swt.graphics.Color(d, rgb);
        gc.setBackground(c);
        gc.fillRectangle(0, 0, 12, 12);
        gc.dispose();
        c.dispose();
        swatches.add(img);
        return img;
    }

    private void disposeSwatches() {
        for (Image img : swatches) if (!img.isDisposed()) img.dispose();
        swatches.clear();
    }

    /** All currently selected tag names (Table's own multi-selection). */
    private java.util.List<String> selectedTags() {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (rowTags == null) return result;
        for (int idx : table.getSelectionIndices())
            if (idx >= 0 && idx < rowTags.size()) result.add(rowTags.get(idx));
        return result;
    }

    private void createTag() {
        InputDialog input = new InputDialog(dlg, "New Tag", "Tag name:");
        String name = input.open();
        if (name == null) return;
        name = name.trim();
        if (name.isBlank()) return;
        // Tags are persisted as a single comma-joined property with no escaping — a literal
        // comma would silently fragment into bogus tags on the next load/save round-trip.
        if (name.contains(",")) { error("Tag names can't contain a comma."); return; }
        if (TagRegistry.exists(name)) { error("Tag \"" + name + "\" already exists."); return; }

        ColorDialog cd = new ColorDialog(dlg);
        cd.setRGB(TagRegistry.getColor(name)); // preview of the auto-assigned color
        RGB chosen = cd.open();
        if (chosen == null) return; // cancelled — don't create a tag with no chosen color
        TagRegistry.create(name, chosen);
        changed = true;
        refreshList();
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
        if (newName == null) return;
        newName = newName.trim();
        if (newName.isBlank() || newName.equals(tag)) return;
        if (newName.contains(",")) { error("Tag names can't contain a comma."); return; }

        TagRegistry.rename(tag, newName);
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

    private void recolorSelectedTag() {
        java.util.List<String> sel = selectedTags();
        if (sel.size() != 1) {
            error(sel.isEmpty() ? "Select a tag first." : "Select a single tag to recolor.");
            return;
        }
        String tag = sel.get(0);
        ColorDialog cd = new ColorDialog(dlg);
        cd.setRGB(TagRegistry.getColor(tag));
        RGB chosen = cd.open();
        if (chosen == null) return;
        TagRegistry.setColor(tag, chosen);
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
        for (String tag : tags) TagRegistry.remove(tag);
        changed = true;
        refreshList();
    }

    private void error(String message) {
        MessageBox mb = new MessageBox(dlg, SWT.ICON_ERROR | SWT.OK);
        mb.setMessage(message);
        mb.open();
    }
}
