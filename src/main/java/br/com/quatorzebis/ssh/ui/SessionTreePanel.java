package br.com.quatorzebis.ssh.ui;

import br.com.quatorzebis.ssh.model.SessionInfo;
import br.com.quatorzebis.ssh.storage.SessionStorage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.*;
import org.eclipse.swt.events.*;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

/**
 * Left-side panel showing the session tree.
 *
 * Tree structure:
 *   [Group name]           ← TreeItem with String data
 *       user@host          ← TreeItem with SessionInfo data
 *   user@host              ← root-level SessionInfo
 */
public class SessionTreePanel {

    private final Composite composite;
    private final Tree      tree;
    private final Shell     shell;

    /** Called when the user wants to open a connection (double-click / Enter / Connect). */
    private final Consumer<SessionInfo> onConnect;

    /** Called after any session/group is created, edited, or deleted. */
    private Runnable onChanged = () -> {};

    /** Item currently being dragged (SessionInfo or String group name). */
    private Object dragging;

    /** Folder icon drawn for group tree items. */
    private final Image folderIcon;
    /** Terminal icon drawn for session tree items. */
    private final Image terminalIcon;

    private Composite mainToolbar;

    // Dark theme colours (same palette as the Sessions welcome pane)
    private final Color colorBg;        // near-black background
    private final Color colorGroup;     // #66CCFF — group headings
    private final Color colorSession;   // #CCCCCC — session items
    private final Color colorSelection; // selection highlight
    private final Color colorHover;     // hover highlight
    private final Font  treeFont;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------
    public SessionTreePanel(Composite parent, Shell shell, Consumer<SessionInfo> onConnect) {
        this.shell     = shell;
        this.onConnect = onConnect;

        Display display = parent.getDisplay();

        colorBg        = new Color(display,   0,   0,   0);
        colorGroup     = new Color(display, 102, 204, 255);
        colorSession   = new Color(display, 204, 204, 204);
        colorSelection = new Color(display,  25,  90, 160);
        colorHover     = new Color(display,  30,  40,  55);
        treeFont       = new Font(display, "Consolas", 10, SWT.NORMAL);

        composite = new Composite(parent, SWT.NONE);
        composite.setBackground(colorBg);
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 0; gl.marginHeight = 0; gl.verticalSpacing = 0;
        composite.setLayout(gl);

        folderIcon    = buildFolderIcon(display);
        terminalIcon  = buildTerminalIcon(display);
        composite.addDisposeListener(e -> {
            folderIcon.dispose(); terminalIcon.dispose();
            colorBg.dispose(); colorGroup.dispose(); colorSession.dispose();
            colorSelection.dispose(); colorHover.dispose();
            treeFont.dispose();
        });

        buildToolbar();

        tree = new Tree(composite, SWT.BORDER | SWT.MULTI | SWT.FULL_SELECTION);
        tree.setBackground(colorBg);
        tree.setForeground(colorSession);
        tree.setFont(treeFont);
        tree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        disableNativeExplorerTheme(tree);

        // Custom selection / hover highlight so the dark theme stays readable
        tree.addListener(SWT.EraseItem, e -> {
            e.detail &= ~SWT.HOT;
            boolean selected = (e.detail & SWT.SELECTED) != 0;
            if (!selected) return;
            GC gc = e.gc;
            Rectangle row = new Rectangle(0, e.y, tree.getClientArea().width, e.height);
            gc.setBackground(colorSelection);
            gc.fillRectangle(row);
            // Draw a subtle left-edge accent line
            gc.setForeground(colorGroup);
            gc.setLineWidth(2);
            gc.drawLine(1, row.y, 1, row.y + row.height - 1);
            e.detail &= ~SWT.SELECTED;   // suppress OS default highlight
        });

        setupListeners();
        setupDragAndDrop();
        buildContextMenu();
        reload();
    }

    /**
     * On Windows, the Tree control's expand/collapse triangle is drawn by the
     * native "Explorer" visual theme, which assumes a light background and
     * renders as a barely-visible glyph on a dark background. Disabling the
     * theme reverts to the classic +/- box, which stays legible on any
     * background colour. No-op on other platforms.
     */
    private void disableNativeExplorerTheme(Tree tree) {
        if (!SWT.getPlatform().equals("win32")) return;
        try {
            Class<?> osClass = Class.forName("org.eclipse.swt.internal.win32.OS");
            java.lang.reflect.Method setWindowTheme = osClass.getMethod(
                "SetWindowTheme", long.class, char[].class, char[].class);
            java.lang.reflect.Field handleField = tree.getClass().getField("handle");
            long handle = handleField.getLong(tree);
            setWindowTheme.invoke(null, handle, new char[1], null);
        } catch (Throwable ignored) {
            // Best-effort cosmetic tweak; safe to skip on unsupported SWT builds.
        }
    }

    // -----------------------------------------------------------------------
    // Toolbar
    // -----------------------------------------------------------------------
    private void buildToolbar() {
        // Plain Buttons instead of a ToolBar/ToolItem: on GTK (Linux), native toolbar
        // buttons draw their label text using the system theme and ignore
        // setForeground/setBackground, making them nearly invisible on this dark
        // panel. Regular Buttons respect the explicit colours on every platform.
        mainToolbar = new Composite(composite, SWT.NONE);
        mainToolbar.setBackground(colorBg);
        mainToolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        RowLayout rl = new RowLayout(SWT.HORIZONTAL);
        rl.spacing = 4; rl.marginWidth = 4; rl.marginHeight = 2; rl.center = true;
        mainToolbar.setLayout(rl);

        Button btnNew = new Button(mainToolbar, SWT.PUSH);
        btnNew.setText("+ Session");
        btnNew.setToolTipText("New session");
        btnNew.addListener(SWT.Selection, e -> newSession(""));

        Button btnGroup = new Button(mainToolbar, SWT.PUSH);
        btnGroup.setText("+ Group");
        btnGroup.setToolTipText("New group");
        btnGroup.addListener(SWT.Selection, e -> newGroup());

        Button btnImport = new Button(mainToolbar, SWT.PUSH);
        btnImport.setText("Import...");
        btnImport.setToolTipText("Import sessions from PuTTY or MobaXterm");
        btnImport.addListener(SWT.Selection, e -> importSessions());

        Button btnRefresh = new Button(mainToolbar, SWT.PUSH);
        btnRefresh.setText("⟳");
        btnRefresh.setToolTipText("Refresh");
        btnRefresh.addListener(SWT.Selection, e -> reload());
    }

    private void importSessions() {
        java.util.List<SessionInfo> selected = new ImportSessionsDialog(shell).open();
        if (selected == null || selected.isEmpty()) return;
        int ok = 0, fail = 0;
        for (SessionInfo s : selected) {
            try { SessionStorage.save(s); ok++; }
            catch (IOException e) { fail++; }
        }
        reload();
        String msg = "Imported " + ok + " session" + (ok == 1 ? "" : "s") + ".";
        if (fail > 0) msg += "\n" + fail + " failed to save.";
        MessageBox mb = new MessageBox(shell, SWT.ICON_INFORMATION | SWT.OK);
        mb.setMessage(msg);
        mb.open();
    }

    // -----------------------------------------------------------------------
    // Listeners
    // -----------------------------------------------------------------------
    private void setupListeners() {
        // SWT.DefaultSelection fires on double-click (and Enter) AFTER native tree
        // processing, so it is safe to read/write the expanded state here.
        tree.addListener(SWT.DefaultSelection, e -> activateSelected());

        // Single Enter key also activates the selected item
        tree.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) activateSelected();
            }
        });
    }

    private void activateSelected() {
        TreeItem[] sel = tree.getSelection();
        if (sel.length == 0) return;
        Object data = sel[0].getData();
        if (data instanceof SessionInfo s) {
            onConnect.accept(s);
        } else if (data instanceof String) {
            TreeItem item = sel[0];
            item.setExpanded(!item.getExpanded());
            // Force a visual refresh — setExpanded alone may not repaint on all platforms
            tree.redraw();
            tree.update();
        }
    }

    // -----------------------------------------------------------------------
    // Context menu
    // -----------------------------------------------------------------------
    private void buildContextMenu() {
        Menu menu = new Menu(tree);
        tree.setMenu(menu);

        menu.addMenuListener(new MenuAdapter() {
            @Override
            public void menuShown(MenuEvent e) {
                for (MenuItem item : menu.getItems()) item.dispose();

                TreeItem[] sel     = tree.getSelection();
                boolean single     = sel.length == 1;
                boolean multi      = sel.length > 1;
                boolean hasSession = single && sel[0].getData() instanceof SessionInfo;
                boolean hasGroup   = single && sel[0].getData() instanceof String;
                boolean allDeletable = sel.length > 0 && java.util.Arrays.stream(sel)
                    .allMatch(ti -> ti.getData() instanceof SessionInfo || ti.getData() instanceof String);
                String  selGroup   = hasGroup ? (String) sel[0].getData() : "";

                addItem(menu, "Connect",    SWT.PUSH,  hasSession,
                        ev -> activateSelected());
                new MenuItem(menu, SWT.SEPARATOR);

                addItem(menu, "New Session",          SWT.PUSH, !multi,
                        ev -> newSession(selGroup));
                addItem(menu, "New Session in Group", SWT.PUSH, hasGroup,
                        ev -> newSession(selGroup));
                addItem(menu, "New Group",            SWT.PUSH, !multi,
                        ev -> newGroup());
                new MenuItem(menu, SWT.SEPARATOR);

                addItem(menu, "Rename",    SWT.PUSH, hasSession,
                        ev -> renameSelected());
                addItem(menu, "Duplicate", SWT.PUSH, hasSession,
                        ev -> duplicateSelected());
                addItem(menu, "Edit",      SWT.PUSH, hasSession,
                        ev -> editSelected());
                addItem(menu, "Delete",    SWT.PUSH, allDeletable,
                        ev -> deleteSelected());
            }
        });
    }

    private void addItem(Menu menu, String text, int style, boolean enabled,
                         Listener action) {
        MenuItem mi = new MenuItem(menu, style);
        mi.setText(text);
        mi.setEnabled(enabled);
        if (enabled) mi.addListener(SWT.Selection, action);
    }

    // -----------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------
    public void newSession(String group) {
        SessionDialog dlg = new SessionDialog(shell, group);
        SessionInfo result = dlg.open();
        if (result != null) { reload(); onChanged.run(); }
    }

    private void newGroup() {
        InputDialog dlg = new InputDialog(shell, "New Group", "Group name:");
        String name = dlg.open();
        if (name == null || name.isBlank()) return;
        try {
            SessionStorage.createGroup(name);
            reload();
        } catch (IOException ex) {
            error("Could not create group:\n" + ex.getMessage());
        }
    }

    private void editSelected() {
        TreeItem[] sel = tree.getSelection();
        if (sel.length == 0 || !(sel[0].getData() instanceof SessionInfo s)) return;
        SessionDialog dlg = new SessionDialog(shell, s.group);
        dlg.setEditing(s);
        SessionInfo updated = dlg.open();
        if (updated != null) { reload(); onChanged.run(); }
    }

    private void renameSelected() {
        TreeItem[] sel = tree.getSelection();
        if (sel.length == 0 || !(sel[0].getData() instanceof SessionInfo s)) return;
        InputDialog dlg = new InputDialog(shell, "Rename Session", "New name:");
        dlg.setInitialValue(s.name.isBlank() ? s.label() : s.name);
        String newName = dlg.open();
        if (newName == null || newName.isBlank()) return;
        s.name = newName.trim();
        String group = s.group;
        try { SessionStorage.save(s); reload(); expandGroup(group); onChanged.run(); }
        catch (IOException ex) { error("Could not rename session:\n" + ex.getMessage()); }
    }

    private void duplicateSelected() {
        TreeItem[] sel = tree.getSelection();
        if (sel.length == 0 || !(sel[0].getData() instanceof SessionInfo src)) return;

        // find an unused name like "MySession 2", "MySession 3", ...
        String baseName = src.name.isBlank() ? src.label() : src.name;
        java.util.Set<String> existing;
        try {
            existing = SessionStorage.loadAll().stream()
                .map(s -> s.name.isBlank() ? s.label() : s.name)
                .collect(java.util.stream.Collectors.toSet());
        } catch (Exception ex) { existing = new java.util.HashSet<>(); }

        int suffix = 2;
        String newName;
        do { newName = baseName + " " + suffix++; } while (existing.contains(newName));

        SessionInfo copy    = new SessionInfo();
        copy.name           = newName;
        copy.host           = src.host;
        copy.port           = src.port;
        copy.username       = src.username;
        copy.authType       = src.authType;
        copy.keyPath        = src.keyPath;
        copy.group          = src.group;
        copy.credentialId   = src.credentialId;
        copy.appearFontSize = src.appearFontSize;
        copy.appearFgR      = src.appearFgR; copy.appearFgG = src.appearFgG; copy.appearFgB = src.appearFgB;
        copy.appearBgR      = src.appearBgR; copy.appearBgG = src.appearBgG; copy.appearBgB = src.appearBgB;

        String group = src.group;
        try { SessionStorage.save(copy); reload(); expandGroup(group); onChanged.run(); }
        catch (IOException ex) { error("Could not duplicate session:\n" + ex.getMessage()); }
    }

    private void expandGroup(String group) {
        if (group == null || group.isBlank()) return;
        for (TreeItem item : tree.getItems()) {
            if (group.equals(item.getData())) { item.setExpanded(true); return; }
        }
    }

    private void deleteSelected() {
        TreeItem[] sel = tree.getSelection();
        if (sel.length == 0) return;

        // Build confirmation message
        long sessionCount = java.util.Arrays.stream(sel)
            .filter(ti -> ti.getData() instanceof SessionInfo).count();
        long groupCount = java.util.Arrays.stream(sel)
            .filter(ti -> ti.getData() instanceof String).count();

        String msg;
        if (sel.length == 1) {
            Object data = sel[0].getData();
            if (data instanceof SessionInfo s)
                msg = "Delete session \"" + s.label() + "\"?";
            else
                msg = "Delete group \"" + data + "\" and all its sessions?";
        } else {
            StringBuilder sb = new StringBuilder("Delete ");
            if (sessionCount > 0) sb.append(sessionCount).append(" session").append(sessionCount > 1 ? "s" : "");
            if (sessionCount > 0 && groupCount > 0) sb.append(" and ");
            if (groupCount > 0) sb.append(groupCount).append(" group").append(groupCount > 1 ? "s (and all their sessions)" : " (and all its sessions)");
            sb.append("?");
            msg = sb.toString();
        }

        MessageBox confirm = new MessageBox(shell, SWT.ICON_QUESTION | SWT.YES | SWT.NO);
        confirm.setText("Delete");
        confirm.setMessage(msg);
        if (confirm.open() != SWT.YES) return;

        try {
            for (TreeItem ti : sel) {
                if (ti.getData() instanceof SessionInfo s)
                    SessionStorage.delete(s);
                else if (ti.getData() instanceof String group)
                    deleteGroupWithSessions(group);
            }
        } catch (IOException ex) { error(ex.getMessage()); return; }

        reload();
        onChanged.run();
    }

    private void deleteGroupWithSessions(String group) throws IOException {
        java.util.List<SessionInfo> all = SessionStorage.loadAll();
        for (SessionInfo s : all) {
            if (group.equals(s.group)) SessionStorage.delete(s);
        }
        SessionStorage.deleteGroup(group);
    }

    // -----------------------------------------------------------------------
    // Reload tree
    // -----------------------------------------------------------------------
    public void reload() {
        if (tree.isDisposed()) return;

        // Remember expanded groups
        Set<String> expanded = new HashSet<>();
        for (TreeItem item : tree.getItems()) {
            if (item.getData() instanceof String g && item.getExpanded()) expanded.add(g);
        }

        tree.removeAll();

        java.util.List<SessionInfo> sessions;
        java.util.List<String>      groups;
        try {
            sessions = SessionStorage.loadAll();
            groups   = SessionStorage.loadGroups();
        } catch (Exception e) {
            return;
        }

        Map<String, TreeItem> groupItems = new LinkedHashMap<>();

        // Add all known groups (even empty ones)
        for (String g : groups) {
            TreeItem gi = new TreeItem(tree, SWT.NONE);
            gi.setText(g);
            gi.setImage(folderIcon);
            gi.setForeground(colorGroup);
            gi.setBackground(colorBg);
            gi.setData(g);
            gi.setExpanded(expanded.isEmpty() || expanded.contains(g));
            groupItems.put(g, gi);
        }

        // Sessions
        for (SessionInfo s : sessions) {
            if (s.group == null || s.group.isBlank()) {
                TreeItem item = new TreeItem(tree, SWT.NONE);
                item.setText(s.label());
                item.setImage(terminalIcon);
                item.setForeground(colorSession);
                item.setBackground(colorBg);
                item.setData(s);
            } else {
                TreeItem parent = groupItems.get(s.group);
                if (parent == null) {
                    parent = new TreeItem(tree, SWT.NONE);
                    parent.setText(s.group);
                    parent.setImage(folderIcon);
                    parent.setForeground(colorGroup);
                    parent.setBackground(colorBg);
                    parent.setData(s.group);
                    parent.setExpanded(true);
                    groupItems.put(s.group, parent);
                }
                TreeItem item = new TreeItem(parent, SWT.NONE);
                item.setText(s.label());
                item.setImage(terminalIcon);
                item.setForeground(colorSession);
                item.setBackground(colorBg);
                item.setData(s);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Drag-and-drop
    // -----------------------------------------------------------------------
    private void setupDragAndDrop() {
        DragSource source = new DragSource(tree, DND.DROP_MOVE);
        source.setTransfer(TextTransfer.getInstance());
        source.addDragListener(new DragSourceAdapter() {
            @Override
            public void dragStart(DragSourceEvent e) {
                TreeItem[] sel = tree.getSelection();
                if (sel.length == 0 || sel[0].getData() == null) { e.doit = false; return; }
                dragging = sel[0].getData();
            }
            @Override
            public void dragSetData(DragSourceEvent e) {
                if (!TextTransfer.getInstance().isSupportedType(e.dataType)) return;
                if      (dragging instanceof SessionInfo s) e.data = "session:" + s.id;
                else if (dragging instanceof String g)      e.data = "group:"   + g;
            }
            @Override
            public void dragFinished(DragSourceEvent e) { dragging = null; }
        });

        DropTarget target = new DropTarget(tree, DND.DROP_MOVE);
        target.setTransfer(TextTransfer.getInstance());
        target.addDropListener(new DropTargetAdapter() {
            @Override
            public void dragOver(DropTargetEvent e) {
                e.detail = DND.DROP_MOVE;
                // Dropping onto a session leaf is not allowed
                if (e.item instanceof TreeItem ti && ti.getData() instanceof SessionInfo) {
                    e.detail = DND.DROP_NONE;
                    return;
                }
                // A group can only live at root — reject drop onto another group
                if (dragging instanceof String &&
                        e.item instanceof TreeItem ti && ti.getData() instanceof String) {
                    e.detail = DND.DROP_NONE;
                    return;
                }
                e.feedback = DND.FEEDBACK_SELECT | DND.FEEDBACK_SCROLL;
            }

            @Override
            public void drop(DropTargetEvent e) {
                if (dragging == null) return;

                // Resolve target group ("" = root)
                String targetGroup = "";
                if (e.item instanceof TreeItem ti && ti.getData() instanceof String g)
                    targetGroup = g;

                try {
                    if (dragging instanceof SessionInfo s) {
                        String cur = s.group == null ? "" : s.group;
                        if (!cur.equals(targetGroup)) {
                            SessionStorage.delete(s);   // remove from old location
                            s.group = targetGroup;
                            SessionStorage.save(s);     // write to new location
                            reload();
                            onChanged.run();
                        }
                    } else if (dragging instanceof String srcGroup) {
                        // Move all sessions from srcGroup to root
                        java.util.List<SessionInfo> all = SessionStorage.loadAll();
                        for (SessionInfo s : all) {
                            if (srcGroup.equals(s.group)) {
                                SessionStorage.delete(s);
                                s.group = "";
                                SessionStorage.save(s);
                            }
                        }
                        SessionStorage.deleteGroup(srcGroup);
                        reload();
                        onChanged.run();
                    }
                } catch (IOException ex) {
                    error("Could not move: " + ex.getMessage());
                }
            }
        });
    }

    // -----------------------------------------------------------------------
    // Preferred width for sash fitting
    // -----------------------------------------------------------------------
    public int getPreferredWidth() {
        GC gc = new GC(tree);
        try {
            gc.setFont(tree.getFont());
            int max = 80;
            for (TreeItem root : tree.getItems()) {
                max = Math.max(max, gc.stringExtent(root.getText()).x + 28);
                for (TreeItem child : root.getItems()) {
                    max = Math.max(max, gc.stringExtent(child.getText()).x + 44);
                }
            }
            // toolbar "⟳" button sets a practical lower bound
            max = Math.max(max, 120);
            return max + 24; // right padding + scrollbar
        } finally {
            gc.dispose();
        }
    }

    // -----------------------------------------------------------------------
    // Folder icon (drawn programmatically, transparent background)
    // -----------------------------------------------------------------------
    private static Image buildFolderIcon(Display display) {
        final int W = 16, H = 14;
        // Use RGB(0,0,0) as the transparent key colour
        PaletteData pal = new PaletteData(0xFF0000, 0x00FF00, 0x0000FF);
        ImageData data  = new ImageData(W, H, 24, pal);
        data.transparentPixel = pal.getPixel(new RGB(0, 0, 0));

        Image img = new Image(display, data);
        GC gc = new GC(img);

        // Transparent fill
        gc.setBackground(new Color(display,   0,   0,   0));
        gc.fillRectangle(0, 0, W, H);

        // Folder body (golden yellow)
        Color body  = new Color(display, 230, 175,  45);
        Color light = new Color(display, 255, 215,  90);
        Color dark  = new Color(display, 155, 110,  15);

        gc.setBackground(body);
        gc.fillRectangle(0, 5, 15, 8);   // main body
        gc.fillRectangle(0, 3,  6, 3);   // tab bump (top-left)

        // Top-edge highlight on body
        gc.setBackground(light);
        gc.fillRectangle(1, 5, 13, 1);

        // Outline
        gc.setForeground(dark);
        gc.drawLine( 0,  3,  5,  3);   // tab top
        gc.drawLine( 5,  3,  6,  4);   // tab right diagonal
        gc.drawLine( 6,  4, 14,  4);   // body top (right of tab)
        gc.drawLine( 0,  3,  0, 12);   // left side
        gc.drawLine( 0, 12, 14, 12);   // bottom
        gc.drawLine(14, 12, 14,  4);   // right side

        body.dispose(); light.dispose(); dark.dispose();
        gc.dispose();
        return img;
    }

    // -----------------------------------------------------------------------
    // Terminal icon (drawn programmatically, transparent background)
    // -----------------------------------------------------------------------
    private static Image buildTerminalIcon(Display display) {
        final int W = 16, H = 14;
        PaletteData pal = new PaletteData(0xFF0000, 0x00FF00, 0x0000FF);
        ImageData data  = new ImageData(W, H, 24, pal);
        data.transparentPixel = pal.getPixel(new RGB(0, 0, 0));

        Image img = new Image(display, data);
        GC gc = new GC(img);

        // Transparent fill
        gc.setBackground(new Color(display, 0, 0, 0));
        gc.fillRectangle(0, 0, W, H);

        // Screen bezel (dark gray border)
        Color bezel  = new Color(display,  70,  70,  70);
        Color screen = new Color(display,  20,  20,  20);
        Color prompt = new Color(display,  80, 200,  80);
        Color stand  = new Color(display,  90,  90,  90);

        // Stand
        gc.setBackground(stand);
        gc.fillRectangle(6, 12, 4, 2);   // stem
        gc.fillRectangle(4, 13, 8, 1);   // base

        // Bezel
        gc.setBackground(bezel);
        gc.fillRoundRectangle(0, 0, 15, 11, 2, 2);

        // Screen area
        gc.setBackground(screen);
        gc.fillRectangle(1, 1, 13, 9);

        // Green prompt "▶ _" on screen
        gc.setForeground(prompt);
        gc.drawText(">", 2, 1, true);
        gc.drawLine(6, 8, 9, 8);   // cursor underline

        bezel.dispose(); screen.dispose(); prompt.dispose(); stand.dispose();
        gc.dispose();
        return img;
    }

    // -----------------------------------------------------------------------
    // Accessors / helpers
    // -----------------------------------------------------------------------
    public Composite getComposite() { return composite; }
    public void setOnChanged(Runnable r) { this.onChanged = r; }

    private void error(String msg) {
        MessageBox mb = new MessageBox(shell, SWT.ICON_ERROR | SWT.OK);
        mb.setText("Error"); mb.setMessage(msg); mb.open();
    }

}
