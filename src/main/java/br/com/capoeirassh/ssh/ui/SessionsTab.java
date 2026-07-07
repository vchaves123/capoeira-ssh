package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.model.SessionInfo;
import br.com.capoeirassh.ssh.storage.BackupBundle;
import br.com.capoeirassh.ssh.storage.SessionStorage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Permanent "Sessions" tab — A+B hybrid home screen.
 *
 * Layout: 52px icon sidebar | main content area
 */
public class SessionsTab {

    private final CTabItem tabItem;

    // Palette colors (disposed on widget dispose)
    private Color cBg;       // #0f0f0f
    private Color cSurface;  // #1a1814
    private Color cGold;     // #E8B84B
    private Color cTerra;    // #C05E1A
    private Color cGreen;    // #7EC8A4
    private Color cAreia;    // #f0ede6
    private Color cDim;      // #6a6258
    private Color cBorder;   // #2a2620
    private Color cMid;      // #252220
    private Color cBlack;    // #111111
    private Color cGrey;     // #555555
    private Color cDark;     // #333333
    private Color cGoldHl;   // #E8B84B22 (approx for sidebar active)

    private final Shell shell;
    private final BiConsumer<SessionInfo, char[]> onConnect;
    private final Runnable onCredentials;
    private final Runnable onAbout;
    private final Supplier<Set<String>> connectedNames;

    // Dynamic content
    private ScrolledComposite scrolled;
    private Composite innerComposite;
    private Label statSessions;
    private Label statGroups;
    private Label statOnline;
    private Composite recentCardsRow;
    private Composite listContainer;
    private Text searchText;
    private Composite aboutIconBox;
    private boolean updateAvailable = false;
    private String  updateVersion   = "";

    // Multi-selection state (ALL SESSIONS list)
    private final java.util.LinkedHashSet<String> selectedIds = new java.util.LinkedHashSet<>();
    private final java.util.List<SessionInfo>     sessionOrder = new java.util.ArrayList<>();
    private String lastClickedId = null;
    private final java.util.Map<String, Composite> rowById = new java.util.HashMap<>();
    private Color cSelected;

    public SessionsTab(CTabFolder folder, Shell shell,
                       BiConsumer<SessionInfo, char[]> onConnect,
                       Runnable onCredentials, Runnable onAbout,
                       Supplier<Set<String>> connectedNames) {

        this.shell = shell;
        this.onConnect = onConnect;
        this.onCredentials = onCredentials;
        this.onAbout = onAbout;
        this.connectedNames = connectedNames;

        tabItem = new CTabItem(folder, SWT.NONE);
        tabItem.setText("  Home  ");

        Display display = folder.getDisplay();
        initColors(display);

        // Root composite: horizontal split (sidebar | main)
        Composite root = new Composite(folder, SWT.NONE);
        root.setBackground(cBg);
        GridLayout rootLayout = new GridLayout(2, false);
        rootLayout.marginWidth = 0; rootLayout.marginHeight = 0;
        rootLayout.horizontalSpacing = 0; rootLayout.verticalSpacing = 0;
        root.setLayout(rootLayout);

        Listener delFilter = e -> {
            // This is a Display-wide key filter, so without this guard it would also fire
            // while a Terminal tab is focused, hijacking arrow keys/Enter meant for the
            // remote shell (e.g. shell history recall) to navigate/connect sessions instead.
            if (tabItem.isDisposed() || tabItem.getParent().getSelection() != tabItem) return;
            if (e.keyCode == SWT.DEL && !selectedIds.isEmpty()) {
                deleteSelectedSessions();
            } else if ((e.keyCode == SWT.ARROW_DOWN || e.keyCode == SWT.ARROW_UP) && !sessionOrder.isEmpty()) {
                int cur = selectedIds.size() == 1
                        ? indexOfId(selectedIds.iterator().next()) : -1;
                int next = e.keyCode == SWT.ARROW_DOWN
                        ? (cur < sessionOrder.size() - 1 ? cur + 1 : 0)
                        : (cur > 0 ? cur - 1 : sessionOrder.size() - 1);
                String nextId = sessionOrder.get(next).id;
                clearSelectionVisuals();
                selectedIds.clear();
                selectedIds.add(nextId);
                lastClickedId = nextId;
                applyRowColor(nextId, cSelected);
                // Scroll the row into view
                Composite row = rowById.get(nextId);
                if (row != null && !row.isDisposed() && scrolled != null && !scrolled.isDisposed()) {
                    Point origin = scrolled.getOrigin();
                    Rectangle rowBounds = row.getBounds();
                    Rectangle visible  = scrolled.getClientArea();
                    if (rowBounds.y < origin.y)
                        scrolled.setOrigin(origin.x, rowBounds.y);
                    else if (rowBounds.y + rowBounds.height > origin.y + visible.height)
                        scrolled.setOrigin(origin.x, rowBounds.y + rowBounds.height - visible.height);
                }
                e.doit = false;
            } else if (e.keyCode == SWT.CR && selectedIds.size() == 1) {
                String id = selectedIds.iterator().next();
                sessionOrder.stream().filter(s -> s.id.equals(id)).findFirst()
                        .ifPresent(s -> onConnect.accept(s, null));
            }
        };
        display.addFilter(SWT.KeyDown, delFilter);
        root.addDisposeListener(e -> { disposeColors(); display.removeFilter(SWT.KeyDown, delFilter); });

        buildSidebar(root, display);
        buildMain(root, display);

        tabItem.setControl(root);

        // Initial load
        reload();
    }

    // -----------------------------------------------------------------------
    // Color management
    // -----------------------------------------------------------------------
    private void initColors(Display d) {
        cBg      = new Color(d,  15,  15,  15);
        cSurface = new Color(d,  26,  24,  20);
        cGold    = new Color(d, 232, 184,  75);
        cTerra   = new Color(d, 192,  94,  26);
        cGreen   = new Color(d,   0, 230, 118);  // #00E676 vivid green
        cAreia   = new Color(d, 240, 237, 230);
        cDim     = new Color(d, 106,  98,  88);
        cBorder  = new Color(d,  42,  38,  32);
        cMid     = new Color(d,  37,  34,  32);
        cBlack   = new Color(d,  17,  17,  17);
        cGrey    = new Color(d,  85,  85,  85);
        cDark    = new Color(d,  51,  51,  51);
        cGoldHl  = new Color(d,  45,  37,  12);   // approximate #E8B84B22
        cSelected = new Color(d,  30,  45,  60);   // dark blue tint for selection
    }

    private void disposeColors() {
        Color[] colors = { cBg, cSurface, cGold, cTerra, cGreen, cAreia,
                           cDim, cBorder, cMid, cBlack, cGrey, cDark, cGoldHl, cSelected };
        for (Color c : colors) { if (c != null && !c.isDisposed()) c.dispose(); }
    }

    // -----------------------------------------------------------------------
    // Icon sidebar (52px)
    // -----------------------------------------------------------------------
    private void buildSidebar(Composite parent, Display display) {
        Composite sidebar = new Composite(parent, SWT.NONE);
        sidebar.setBackground(cSurface);
        GridData gdSide = new GridData(SWT.FILL, SWT.FILL, false, true);
        gdSide.widthHint = 52;
        sidebar.setLayoutData(gdSide);

        // Draw right border
        sidebar.addListener(SWT.Paint, e -> {
            if (sidebar.isDisposed()) return;
            Rectangle bounds = sidebar.getBounds();
            e.gc.setForeground(cBorder);
            e.gc.drawLine(bounds.width - 1, 0, bounds.width - 1, bounds.height);
        });

        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 0; gl.marginHeight = 8;
        gl.verticalSpacing = 4;
        sidebar.setLayout(gl);

        // Sessions icon — active
        Composite sessionsIcon = createSidebarIcon(sidebar, display, "🖥", true);
        sessionsIcon.setLayoutData(new GridData(SWT.CENTER, SWT.TOP, true, false));

        // Credentials icon — emoji reflects vault lock state
        br.com.capoeirassh.ssh.storage.CredentialStore cs =
                br.com.capoeirassh.ssh.storage.CredentialStore.getInstance();
        Composite credsIcon = createSidebarIcon(sidebar, display,
                cs.isUnlocked() ? "🔓" : "🔒", false);
        credsIcon.setLayoutData(new GridData(SWT.CENTER, SWT.TOP, true, false));
        credsIcon.setToolTipText("Credentials vault");
        credsIcon.addListener(SWT.MouseUp, e -> onCredentials.run());
        for (Control c : credsIcon.getChildren()) c.addListener(SWT.MouseUp, e -> onCredentials.run());

        // Keep reference to the label inside credsIcon for live updates
        Label credsLbl = (Label) credsIcon.getChildren()[0];

        // Direct update — must be called from the UI thread
        Runnable applyCredsIcon = () -> {
            if (credsLbl.isDisposed()) return;
            boolean unlocked = cs.isUnlocked();
            credsLbl.setText(unlocked ? "🔓" : "🔒");
            credsIcon.setToolTipText(unlocked ? "Credentials vault (unlocked)" : "Credentials vault (locked)");
            credsLbl.redraw();
            credsIcon.redraw();
        };

        // Callback fires from any thread (auto-lock = background, unlock = UI) → always asyncExec
        cs.setOnLockCallback(() -> display.asyncExec(applyCredsIcon));

        // Refresh icon after credentials dialog closes (unlock may have happened)
        credsIcon.addListener(SWT.MouseUp, e -> applyCredsIcon.run());
        for (Control c : credsIcon.getChildren()) c.addListener(SWT.MouseUp, e -> applyCredsIcon.run());

        // Settings icon
        Composite settingsIcon = createSidebarIcon(sidebar, display, "⚙", false);
        settingsIcon.setLayoutData(new GridData(SWT.CENTER, SWT.TOP, true, false));
        settingsIcon.addListener(SWT.MouseUp, e -> openSettings());
        for (Control c : settingsIcon.getChildren()) c.addListener(SWT.MouseUp, e -> openSettings());

        // Spacer
        Label spacer = new Label(sidebar, SWT.NONE);
        spacer.setBackground(cSurface);
        spacer.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // Unified import/export icon
        Composite ioIcon = createSidebarIcon(sidebar, display, "⇅", false);
        ioIcon.setLayoutData(new GridData(SWT.CENTER, SWT.BOTTOM, true, false));
        ioIcon.setToolTipText("Import / Export");
        Runnable openIoMenu = () -> openImportExportMenu(ioIcon);
        ioIcon.addListener(SWT.MouseUp, e -> openIoMenu.run());
        for (Control c : ioIcon.getChildren()) c.addListener(SWT.MouseUp, e -> openIoMenu.run());

        // About icon (bottom)
        aboutIconBox = createSidebarIcon(sidebar, display, "ℹ", false);
        aboutIconBox.setLayoutData(new GridData(SWT.CENTER, SWT.BOTTOM, true, false));
        aboutIconBox.addListener(SWT.MouseUp, e -> onAbout.run());
        for (Control c : aboutIconBox.getChildren()) c.addListener(SWT.MouseUp, e -> onAbout.run());

        // Update badge overlay on the about icon
        aboutIconBox.addListener(SWT.Paint, e -> {
            if (!updateAvailable) return;
            int sz = 8;
            Rectangle b = aboutIconBox.getClientArea();
            e.gc.setBackground(cGold);
            e.gc.fillOval(b.width - sz - 2, 2, sz, sz);
        });
    }

    private Composite createSidebarIcon(Composite parent, Display display, String emoji, boolean active) {
        Composite box = new Composite(parent, SWT.NONE) {
            @Override
            public Point computeSize(int wHint, int hHint, boolean changed) {
                return new Point(36, 36);
            }
        };
        box.setBackground(active ? cGoldHl : cSurface);

        if (active) {
            box.addListener(SWT.Paint, e -> {
                e.gc.setForeground(cGold);
                e.gc.setLineWidth(3);
                e.gc.drawLine(0, 0, 0, 36);
            });
        }

        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 0; gl.marginHeight = 0;
        box.setLayout(gl);

        Label lbl = new Label(box, SWT.CENTER);
        lbl.setText(emoji);
        lbl.setBackground(active ? cGoldHl : cSurface);
        lbl.setForeground(active ? cGold : cAreia);
        lbl.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Font emojiFont = new Font(display, "Segoe UI Emoji", 14, SWT.NORMAL);
        lbl.setFont(emojiFont);
        lbl.addDisposeListener(e -> emojiFont.dispose());

        Cursor hand = display.getSystemCursor(SWT.CURSOR_HAND);
        if (!active) {
            box.setCursor(hand);
            lbl.setCursor(hand);

            box.addListener(SWT.MouseEnter, e -> { box.setBackground(cMid); lbl.setBackground(cMid); box.redraw(); });
            box.addListener(SWT.MouseExit,  e -> { box.setBackground(cSurface); lbl.setBackground(cSurface); box.redraw(); });
            lbl.addListener(SWT.MouseEnter, e -> { box.setBackground(cMid); lbl.setBackground(cMid); box.redraw(); });
            lbl.addListener(SWT.MouseExit,  e -> { box.setBackground(cSurface); lbl.setBackground(cSurface); box.redraw(); });
        }

        return box;
    }

    // -----------------------------------------------------------------------
    // Main content area
    // -----------------------------------------------------------------------
    private void buildMain(Composite parent, Display display) {
        Composite main = new Composite(parent, SWT.NONE);
        main.setBackground(cBg);
        main.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 0; gl.marginHeight = 0;
        gl.verticalSpacing = 0;
        main.setLayout(gl);

        buildHeader(main, display);

        // Scrolled area
        scrolled = new ScrolledComposite(main, SWT.V_SCROLL | SWT.H_SCROLL);
        scrolled.setBackground(cBg);
        scrolled.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        scrolled.setExpandHorizontal(true);
        scrolled.setExpandVertical(true);

        innerComposite = new Composite(scrolled, SWT.NONE);
        innerComposite.setBackground(cBg);
        GridLayout innerGl = new GridLayout(1, false);
        innerGl.marginWidth = 20; innerGl.marginHeight = 20;
        innerGl.verticalSpacing = 0;
        innerComposite.setLayout(innerGl);

        buildStatsRow(innerComposite, display);
        buildRecentSection(innerComposite, display);
        buildAllSessionsSection(innerComposite, display);

        scrolled.setContent(innerComposite);
        int cw0 = scrolled.getClientArea().width;
        scrolled.setMinSize(innerComposite.computeSize(cw0 > 0 ? cw0 : SWT.DEFAULT, SWT.DEFAULT));
    }

    // -----------------------------------------------------------------------
    // Header bar
    // -----------------------------------------------------------------------
    private void buildHeader(Composite parent, Display display) {
        Composite header = new Composite(parent, SWT.NONE);
        header.setBackground(cSurface);
        header.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        header.addListener(SWT.Paint, e -> {
            Rectangle b = header.getBounds();
            e.gc.setForeground(cBorder);
            e.gc.drawLine(0, b.height - 1, b.width, b.height - 1);
        });

        GridLayout gl = new GridLayout(3, false);
        gl.marginWidth = 14; gl.marginHeight = 10;
        gl.horizontalSpacing = 12;
        header.setLayout(gl);

        // Logo block
        Composite logoBlock = new Composite(header, SWT.NONE);
        logoBlock.setBackground(cSurface);
        logoBlock.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        GridLayout logoGl = new GridLayout(1, false);
        logoGl.marginWidth = 0; logoGl.marginHeight = 0;
        logoGl.verticalSpacing = 1;
        logoBlock.setLayout(logoGl);

        Label logoName = new Label(logoBlock, SWT.NONE);
        logoName.setText("Capoeira");
        logoName.setBackground(cSurface);
        logoName.setForeground(cGold);
        Font boldFont = new Font(display, "Arial", 18, SWT.BOLD);
        logoName.setFont(boldFont);
        logoName.addDisposeListener(e -> boldFont.dispose());
        logoName.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        Label logoSub = new Label(logoBlock, SWT.NONE);
        logoSub.setText("SSH CLIENT");
        logoSub.setBackground(cSurface);
        logoSub.setForeground(cDim);
        Font subFont = new Font(display, "Arial", 8, SWT.NORMAL);
        logoSub.setFont(subFont);
        logoSub.addDisposeListener(e -> subFont.dispose());
        logoSub.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        // Spring filler
        Label filler = new Label(header, SWT.NONE);
        filler.setBackground(cSurface);
        filler.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Right side: search + new button
        Composite rightBar = new Composite(header, SWT.NONE);
        rightBar.setBackground(cSurface);
        rightBar.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        RowLayout rl = new RowLayout(SWT.HORIZONTAL);
        rl.spacing = 8; rl.marginWidth = 0; rl.marginHeight = 0;
        rl.center = true;
        rightBar.setLayout(rl);

        searchText = new Text(rightBar, SWT.BORDER | SWT.SINGLE);
        searchText.setMessage("Search sessions…");
        searchText.setBackground(cBg);
        searchText.setForeground(cGrey);
        RowData rdSearch = new RowData(200, SWT.DEFAULT);
        searchText.setLayoutData(rdSearch);
        searchText.addListener(SWT.Modify, e -> filterList(searchText.getText()));

        Button btnNew = new Button(rightBar, SWT.PUSH);
        btnNew.setText("+ New Session");
        btnNew.setBackground(cGold);
        btnNew.setForeground(cBg);
        Font boldBtn = new Font(display, "Arial", 9, SWT.BOLD);
        btnNew.setFont(boldBtn);
        btnNew.addDisposeListener(e -> boldBtn.dispose());
        btnNew.addListener(SWT.Selection, e -> newSession());
    }

    // -----------------------------------------------------------------------
    // Stats row
    // -----------------------------------------------------------------------
    private void buildStatsRow(Composite parent, Display display) {
        Composite statsRow = new Composite(parent, SWT.NONE);
        statsRow.setBackground(cBg);
        GridData gdStats = new GridData(SWT.FILL, SWT.TOP, true, false);
        statsRow.setLayoutData(gdStats);
        GridLayout gl = new GridLayout(3, false);
        gl.horizontalSpacing = 8; gl.marginWidth = 0; gl.marginHeight = 0;
        statsRow.setLayout(gl);

        Font numFont = new Font(display, "Arial", 18, SWT.BOLD);
        statsRow.addDisposeListener(e -> numFont.dispose());

        Font lblSmFont = new Font(display, "Arial", 8, SWT.NORMAL);
        statsRow.addDisposeListener(e -> lblSmFont.dispose());

        statSessions = buildStatBox(statsRow, display, "0", "SESSIONS", cGold,  numFont, lblSmFont);
        statGroups   = buildStatBox(statsRow, display, "0", "GROUPS",   cTerra, numFont, lblSmFont);
        statOnline   = buildStatBox(statsRow, display, "0", "ONLINE",   cGreen, numFont, lblSmFont);
    }

    /** Creates one stat box and returns the Label that holds the number. */
    private Label buildStatBox(Composite parent, Display display,
                               String number, String label,
                               Color numColor, Font numFont, Font lblFont) {
        Composite box = new Composite(parent, SWT.NONE);
        box.setBackground(cSurface);
        box.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        box.addListener(SWT.Paint, e -> {
            Rectangle b = box.getBounds();
            e.gc.setForeground(cMid);
            e.gc.drawRoundRectangle(0, 0, b.width - 1, b.height - 1, 8, 8);
        });

        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 14; gl.marginHeight = 10;
        gl.verticalSpacing = 3;
        box.setLayout(gl);

        Label numLbl = new Label(box, SWT.NONE);
        numLbl.setText(number);
        numLbl.setBackground(cSurface);
        numLbl.setForeground(numColor);
        numLbl.setFont(numFont);
        numLbl.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        Label txtLbl = new Label(box, SWT.NONE);
        txtLbl.setText(label);
        txtLbl.setBackground(cSurface);
        txtLbl.setForeground(cDim);
        txtLbl.setFont(lblFont);
        txtLbl.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        return numLbl;
    }

    // -----------------------------------------------------------------------
    // Recent section
    // -----------------------------------------------------------------------
    private void buildRecentSection(Composite parent, Display display) {
        Label recentHeader = new Label(parent, SWT.NONE);
        recentHeader.setText("RECENT");
        recentHeader.setBackground(cBg);
        recentHeader.setForeground(cDim);
        Font hdrFont = new Font(display, "Arial", 9, SWT.NORMAL);
        recentHeader.setFont(hdrFont);
        recentHeader.addDisposeListener(e -> hdrFont.dispose());
        GridData gdHdr = new GridData(SWT.FILL, SWT.TOP, true, false);
        gdHdr.verticalIndent = 20;
        recentHeader.setLayoutData(gdHdr);

        recentCardsRow = new Composite(parent, SWT.NONE);
        recentCardsRow.setBackground(cBg);
        GridData gdCards = new GridData(SWT.FILL, SWT.TOP, true, false);
        gdCards.verticalIndent = 8;
        recentCardsRow.setLayoutData(gdCards);
        GridLayout gl = new GridLayout(3, false);
        gl.horizontalSpacing = 8; gl.marginWidth = 0; gl.marginHeight = 0;
        recentCardsRow.setLayout(gl);
    }

    // -----------------------------------------------------------------------
    // All Sessions section
    // -----------------------------------------------------------------------
    private void buildAllSessionsSection(Composite parent, Display display) {
        Label allHeader = new Label(parent, SWT.NONE);
        allHeader.setText("ALL SESSIONS");
        allHeader.setBackground(cBg);
        allHeader.setForeground(cDim);
        Font hdrFont = new Font(display, "Arial", 9, SWT.NORMAL);
        allHeader.setFont(hdrFont);
        allHeader.addDisposeListener(e -> hdrFont.dispose());
        GridData gdHdr = new GridData(SWT.FILL, SWT.TOP, true, false);
        gdHdr.verticalIndent = 20;
        allHeader.setLayoutData(gdHdr);

        listContainer = new Composite(parent, SWT.NONE);
        listContainer.setBackground(cBg);
        GridData gdList = new GridData(SWT.FILL, SWT.TOP, true, false);
        gdList.verticalIndent = 4;
        listContainer.setLayoutData(gdList);
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 0; gl.marginHeight = 0;
        gl.verticalSpacing = 2;
        listContainer.setLayout(gl);
    }

    // -----------------------------------------------------------------------
    // reload() — rebuild dynamic content
    // -----------------------------------------------------------------------
    public void reload() {
        List<SessionInfo> sessions = SessionStorage.loadAll();
        List<String> groups;
        try { groups = SessionStorage.loadGroups(); } catch (Exception ex) { groups = List.of(); }

        Set<String> online = connectedNames != null ? connectedNames.get() : Set.of();

        Display display = shell.getDisplay();

        // Update stats
        if (statSessions != null && !statSessions.isDisposed())
            statSessions.setText(String.valueOf(sessions.size()));
        if (statGroups != null && !statGroups.isDisposed())
            statGroups.setText(String.valueOf(groups.size()));
        if (statOnline != null && !statOnline.isDisposed())
            statOnline.setText(String.valueOf(online.size()));

        // Rebuild recent cards
        if (recentCardsRow != null && !recentCardsRow.isDisposed()) {
            for (Control c : recentCardsRow.getChildren()) c.dispose();

            int cardCount = Math.min(sessions.size(), 3);
            for (int i = 0; i < cardCount; i++) {
                buildRecentCard(recentCardsRow, display, sessions.get(i));
            }
            // Fill remaining slots with add-card (only if < 3 real sessions)
            if (sessions.size() < 3) {
                buildAddCard(recentCardsRow, display);
            }
            recentCardsRow.layout(true, true);
        }

        // Rebuild list
        if (listContainer != null && !listContainer.isDisposed()) {
            for (Control c : listContainer.getChildren()) c.dispose();
            selectedIds.clear();
            lastClickedId = null;
            sessionOrder.clear();
            sessionOrder.addAll(sessions);
            rowById.clear();
            for (SessionInfo s : sessions) {
                buildListRow(listContainer, display, s, online.contains(s.name));
            }
            listContainer.layout(true, true);
        }

        // Reapply search filter
        if (searchText != null && !searchText.isDisposed()) {
            filterList(searchText.getText());
        }

        // Update scroll size
        if (scrolled != null && !scrolled.isDisposed() && innerComposite != null && !innerComposite.isDisposed()) {
            innerComposite.layout(true, true);
            int cw = scrolled.getClientArea().width;
            scrolled.setMinSize(innerComposite.computeSize(cw > 0 ? cw : SWT.DEFAULT, SWT.DEFAULT));
        }
    }

    // -----------------------------------------------------------------------
    // Recent card
    // -----------------------------------------------------------------------
    private void buildRecentCard(Composite parent, Display display, SessionInfo session) {
        Composite card = new Composite(parent, SWT.NONE);
        card.setBackground(cSurface);
        card.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        card.addListener(SWT.Paint, e -> {
            Rectangle b = card.getBounds();
            e.gc.setForeground(cMid);
            e.gc.drawRoundRectangle(0, 0, b.width - 1, b.height - 1, 10, 10);
        });

        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 12; gl.marginHeight = 12;
        gl.verticalSpacing = 5;
        card.setLayout(gl);

        // Top row: dot + name + connect link
        Composite topRow = new Composite(card, SWT.NONE);
        topRow.setBackground(cSurface);
        topRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout topGl = new GridLayout(3, false);
        topGl.marginWidth = 0; topGl.marginHeight = 0;
        topGl.horizontalSpacing = 6;
        topRow.setLayout(topGl);

        Canvas dot = new Canvas(topRow, SWT.NONE);
        dot.setBackground(cSurface);
        GridData gdDot = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        gdDot.widthHint = 7; gdDot.heightHint = 7;
        dot.setLayoutData(gdDot);
        dot.addListener(SWT.Paint, e -> {
            e.gc.setBackground(cGrey);
            e.gc.fillOval(0, 0, 7, 7);
        });

        Label nameLbl = new Label(topRow, SWT.NONE);
        nameLbl.setText(session.name != null ? session.name : "");
        nameLbl.setBackground(cSurface);
        nameLbl.setForeground(cAreia);
        Font boldF = new Font(display, "Arial", 12, SWT.BOLD);
        nameLbl.setFont(boldF);
        nameLbl.addDisposeListener(e -> boldF.dispose());
        nameLbl.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label connectLnk = new Label(topRow, SWT.NONE);
        connectLnk.setText("Connect →");
        connectLnk.setBackground(cSurface);
        connectLnk.setForeground(cGold);
        Font lnkF = new Font(display, "Arial", 10, SWT.NORMAL);
        connectLnk.setFont(lnkF);
        connectLnk.addDisposeListener(e -> lnkF.dispose());
        connectLnk.setCursor(display.getSystemCursor(SWT.CURSOR_HAND));
        connectLnk.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        connectLnk.addListener(SWT.MouseDoubleClick, e -> onConnect.accept(session, null));

        // Host
        Label hostLbl = new Label(card, SWT.NONE);
        String hostText = (session.host != null ? session.host : "") +
            (session.port != 22 ? ":" + session.port : "");
        hostLbl.setText(hostText);
        hostLbl.setBackground(cSurface);
        hostLbl.setForeground(cDim);
        Font hostF = new Font(display, "Arial", 10, SWT.NORMAL);
        hostLbl.setFont(hostF);
        hostLbl.addDisposeListener(e -> hostF.dispose());
        hostLbl.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Group badge
        if (session.group != null && !session.group.isEmpty()) {
            buildGroupBadge(card, display, session.group);
        }

        // Double-click card → connect
        addDoubleClickRecursive(card, session);
    }

    private void buildAddCard(Composite parent, Display display) {
        Composite card = new Composite(parent, SWT.NONE);
        card.setBackground(cSurface);
        card.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        card.setCursor(display.getSystemCursor(SWT.CURSOR_HAND));

        card.addListener(SWT.Paint, e -> {
            Rectangle b = card.getBounds();
            e.gc.setForeground(cBorder);
            // dashed border via segments
            int dash = 6;
            int perim = 2 * (b.width + b.height);
            boolean draw = true;
            int x = 0, y = 0;
            // simple dashed rectangle approximation — top
            for (int px = 0; px < b.width; px += dash) {
                if (draw) e.gc.drawLine(px, 0, Math.min(px + dash, b.width), 0);
                draw = !draw;
            }
            draw = true;
            for (int py = 0; py < b.height; py += dash) {
                if (draw) e.gc.drawLine(b.width - 1, py, b.width - 1, Math.min(py + dash, b.height));
                draw = !draw;
            }
            draw = true;
            for (int px = b.width; px > 0; px -= dash) {
                if (draw) e.gc.drawLine(px, b.height - 1, Math.max(px - dash, 0), b.height - 1);
                draw = !draw;
            }
            draw = true;
            for (int py = b.height; py > 0; py -= dash) {
                if (draw) e.gc.drawLine(0, py, 0, Math.max(py - dash, 0));
                draw = !draw;
            }
        });

        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 12; gl.marginHeight = 12;
        card.setLayout(gl);

        Label plusLbl = new Label(card, SWT.CENTER);
        plusLbl.setText("+  New Session");
        plusLbl.setBackground(cSurface);
        plusLbl.setForeground(cDim);
        plusLbl.setCursor(display.getSystemCursor(SWT.CURSOR_HAND));
        plusLbl.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, true));
        GridData gdPlus = new GridData(SWT.CENTER, SWT.CENTER, true, false);
        gdPlus.heightHint = 50;
        plusLbl.setLayoutData(gdPlus);

        card.addListener(SWT.MouseUp, e -> newSession());
        plusLbl.addListener(SWT.MouseUp, e -> newSession());
    }

    // -----------------------------------------------------------------------
    // Group badge
    // -----------------------------------------------------------------------
    private void buildGroupBadge(Composite parent, Display display, String group) {
        Composite badge = new Composite(parent, SWT.NONE);
        badge.setBackground(cBlack);
        badge.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        badge.addListener(SWT.Paint, e -> {
            Rectangle b = badge.getBounds();
            e.gc.setForeground(cBorder);
            e.gc.drawRoundRectangle(0, 0, b.width - 1, b.height - 1, 3, 3);
        });

        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 5; gl.marginHeight = 2;
        badge.setLayout(gl);

        Label lbl = new Label(badge, SWT.NONE);
        lbl.setText(group);
        lbl.setBackground(cBlack);
        lbl.setForeground(cDim);
        Font f = new Font(display, "Arial", 8, SWT.NORMAL);
        lbl.setFont(f);
        lbl.addDisposeListener(e -> f.dispose());
        lbl.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, false, false));
    }

    // -----------------------------------------------------------------------
    // Session list row
    // -----------------------------------------------------------------------
    private void buildListRow(Composite parent, Display display, SessionInfo session, boolean isOnline) {
        Composite row = new Composite(parent, SWT.NONE);
        row.setBackground(cBg);
        row.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        // Store session for filtering
        row.setData("session", session);

        GridLayout gl = new GridLayout(5, false);
        gl.marginWidth = 12; gl.marginHeight = 8;
        gl.horizontalSpacing = 10;
        row.setLayout(gl);

        rowById.put(session.id, row);

        // Hover / selection background
        Color normalBg = selectedIds.contains(session.id) ? cSelected : cBg;
        row.setBackground(normalBg);
        refreshChildren(row, normalBg);

        row.addListener(SWT.MouseEnter, e -> {
            if (!selectedIds.contains(session.id)) { row.setBackground(cSurface); refreshChildren(row, cSurface); }
        });
        row.addListener(SWT.MouseExit, e -> {
            Point cursor = row.getDisplay().getCursorLocation();
            Point local  = row.toControl(cursor);
            if (!row.getClientArea().contains(local)) {
                Color bg = selectedIds.contains(session.id) ? cSelected : cBg;
                row.setBackground(bg); refreshChildren(row, bg);
            }
        });

        // Avatar (28x28 rounded square)
        Canvas avatar = new Canvas(row, SWT.NONE);
        avatar.setBackground(cBg);
        GridData gdAv = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        gdAv.widthHint = 28; gdAv.heightHint = 28;
        avatar.setLayoutData(gdAv);
        String initials = (session.name != null && !session.name.isEmpty())
            ? String.valueOf(session.name.charAt(0)).toUpperCase() : "?";
        avatar.addListener(SWT.Paint, e -> {
            e.gc.setBackground(cMid);
            e.gc.fillRoundRectangle(0, 0, 28, 28, 6, 6);
            e.gc.setForeground(cGold);
            Font avF = new Font(display, "Arial", 11, SWT.BOLD);
            e.gc.setFont(avF);
            Point ext = e.gc.stringExtent(initials);
            e.gc.drawString(initials, (28 - ext.x) / 2, (28 - ext.y) / 2, true);
            avF.dispose();
        });

        // Name + host column
        Composite nameCol = new Composite(row, SWT.NONE);
        nameCol.setBackground(cBg);
        nameCol.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout nameGl = new GridLayout(1, false);
        nameGl.marginWidth = 0; nameGl.marginHeight = 0;
        nameGl.verticalSpacing = 2;
        nameCol.setLayout(nameGl);

        Label nameL = new Label(nameCol, SWT.NONE);
        nameL.setText(session.name != null ? session.name : "");
        nameL.setBackground(cBg);
        nameL.setForeground(cAreia);
        Font nameF = new Font(display, "Arial", 11, SWT.BOLD);
        nameL.setFont(nameF);
        nameL.addDisposeListener(e -> nameF.dispose());
        nameL.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label hostL = new Label(nameCol, SWT.NONE);
        String hostStr = (session.host != null ? session.host : "") +
            (session.port != 22 ? ":" + session.port : "");
        hostL.setText(hostStr);
        hostL.setBackground(cBg);
        hostL.setForeground(cDim);
        Font hostF = new Font(display, "Arial", 10, SWT.NORMAL);
        hostL.setFont(hostF);
        hostL.addDisposeListener(e -> hostF.dispose());
        hostL.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Group badge column
        if (session.group != null && !session.group.isEmpty()) {
            buildGroupBadge(row, display, session.group);
        } else {
            Label gap = new Label(row, SWT.NONE);
            gap.setBackground(cBg);
        }

        // Status dot
        Canvas statusDot = new Canvas(row, SWT.NONE);
        statusDot.setBackground(cBg);
        GridData gdSd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        gdSd.widthHint = 6; gdSd.heightHint = 6;
        statusDot.setLayoutData(gdSd);
        statusDot.addListener(SWT.Paint, e -> {
            e.gc.setBackground(isOnline ? cGreen : cGrey);
            e.gc.fillOval(0, 0, 6, 6);
        });

        // Arrow
        Label arrow = new Label(row, SWT.NONE);
        arrow.setText("›");
        arrow.setBackground(cBg);
        arrow.setForeground(cDark);
        arrow.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));

        // Single-click → select / Ctrl+click → toggle / Shift+click → range
        // Double-click → connect
        addSelectionClickRecursive(row, session);

        // Right-click context menu (rebuilt dynamically at show time)
        Menu menu = new Menu(row);
        menu.addListener(SWT.Show, e -> {
            for (MenuItem it : menu.getItems()) it.dispose();

            int selCount = selectedIds.size();
            boolean multiSelected = selCount > 1 && selectedIds.contains(session.id);

            if (!multiSelected) {
                // Ensure the right-clicked session is selected
                if (!selectedIds.contains(session.id)) {
                    clearSelectionVisuals();
                    selectedIds.clear();
                    selectedIds.add(session.id);
                    lastClickedId = session.id;
                    applyRowColor(session.id, cSelected);
                }

                MenuItem miConnect = new MenuItem(menu, SWT.PUSH);
                miConnect.setText("Connect");
                miConnect.addListener(SWT.Selection, ev -> onConnect.accept(session, null));

                new MenuItem(menu, SWT.SEPARATOR);

                MenuItem miEdit = new MenuItem(menu, SWT.PUSH);
                miEdit.setText("Edit");
                miEdit.addListener(SWT.Selection, ev -> editSession(session));

                MenuItem miDup = new MenuItem(menu, SWT.PUSH);
                miDup.setText("Copy");
                miDup.addListener(SWT.Selection, ev -> duplicateSession(session));

                new MenuItem(menu, SWT.SEPARATOR);

                MenuItem miDelete = new MenuItem(menu, SWT.PUSH);
                miDelete.setText("Delete");
                miDelete.addListener(SWT.Selection, ev -> deleteSession(session));
            } else {
                MenuItem miDeleteAll = new MenuItem(menu, SWT.PUSH);
                miDeleteAll.setText("Delete " + selCount + " sessions");
                miDeleteAll.addListener(SWT.Selection, ev -> deleteSelectedSessions());
            }
        });

        setMenuRecursive(row, menu);
    }

    private void setMenuRecursive(Control ctrl, Menu menu) {
        ctrl.setMenu(menu);
        if (ctrl instanceof Composite)
            for (Control c : ((Composite) ctrl).getChildren()) setMenuRecursive(c, menu);
    }

    private void refreshChildren(Composite comp, Color bg) {
        for (Control c : comp.getChildren()) {
            c.setBackground(bg);
            if (c instanceof Composite) refreshChildren((Composite) c, bg);
        }
    }

    private void addDoubleClickRecursive(Control ctrl, SessionInfo session) {
        ctrl.addListener(SWT.MouseDoubleClick, e -> { if (e.button == 1) onConnect.accept(session, null); });
        if (ctrl instanceof Composite)
            for (Control c : ((Composite) ctrl).getChildren()) addDoubleClickRecursive(c, session);
    }

    private void addClickRecursive(Control ctrl, Listener listener) {
        ctrl.addListener(SWT.MouseUp, e -> { if (e.button == 1) listener.handleEvent(e); });
        if (ctrl instanceof Composite) {
            for (Control c : ((Composite) ctrl).getChildren()) addClickRecursive(c, listener);
        }
    }

    private void addSelectionClickRecursive(Control ctrl, SessionInfo session) {
        ctrl.addListener(SWT.MouseDown, e -> {
            if (e.button != 1) return;
            boolean ctrl_ = (e.stateMask & SWT.CTRL)  != 0;
            boolean shift = (e.stateMask & SWT.SHIFT) != 0;
            if (ctrl_) {
                if (selectedIds.contains(session.id)) {
                    selectedIds.remove(session.id);
                    applyRowColor(session.id, cBg);
                } else {
                    selectedIds.add(session.id);
                    applyRowColor(session.id, cSelected);
                    lastClickedId = session.id;
                }
            } else if (shift && lastClickedId != null) {
                int from = indexOfId(lastClickedId);
                int to   = indexOfId(session.id);
                if (from >= 0 && to >= 0) {
                    clearSelectionVisuals();
                    selectedIds.clear();
                    int lo = Math.min(from, to), hi = Math.max(from, to);
                    for (int i = lo; i <= hi; i++) {
                        String id = sessionOrder.get(i).id;
                        selectedIds.add(id);
                        applyRowColor(id, cSelected);
                    }
                }
            } else {
                clearSelectionVisuals();
                selectedIds.clear();
                selectedIds.add(session.id);
                applyRowColor(session.id, cSelected);
                lastClickedId = session.id;
            }
        });
        ctrl.addListener(SWT.MouseDoubleClick, e -> {
            if (e.button == 1) onConnect.accept(session, null);
        });
        if (ctrl instanceof Composite) {
            for (Control c : ((Composite) ctrl).getChildren())
                addSelectionClickRecursive(c, session);
        }
    }

    private int indexOfId(String id) {
        for (int i = 0; i < sessionOrder.size(); i++)
            if (sessionOrder.get(i).id.equals(id)) return i;
        return -1;
    }

    private void applyRowColor(String id, Color color) {
        Composite row = rowById.get(id);
        if (row != null && !row.isDisposed()) { row.setBackground(color); refreshChildren(row, color); }
    }

    private void clearSelectionVisuals() {
        for (String id : selectedIds) applyRowColor(id, cBg);
    }

    private void deleteSelectedSessions() {
        if (selectedIds.isEmpty()) return;
        int count = selectedIds.size();
        MessageBox mb = new MessageBox(shell, SWT.ICON_QUESTION | SWT.YES | SWT.NO);
        mb.setText("Delete Sessions");
        mb.setMessage("Delete " + count + " selected session" + (count == 1 ? "" : "s") + "?");
        if (mb.open() != SWT.YES) return;
        for (String id : new java.util.ArrayList<>(selectedIds)) {
            sessionOrder.stream().filter(s -> s.id.equals(id)).findFirst().ifPresent(s -> {
                try { SessionStorage.delete(s); } catch (Exception ignored) {}
            });
        }
        selectedIds.clear();
        lastClickedId = null;
        reload();
    }

    // -----------------------------------------------------------------------
    // Search / filter
    // -----------------------------------------------------------------------
    private void filterList(String query) {
        if (listContainer == null || listContainer.isDisposed()) return;
        String q = query == null ? "" : query.trim().toLowerCase();
        for (Control c : listContainer.getChildren()) {
            if (!(c instanceof Composite)) continue;
            Object data = c.getData("session");
            if (!(data instanceof SessionInfo)) continue;
            SessionInfo s = (SessionInfo) data;
            boolean match = q.isEmpty()
                || (s.name  != null && s.name.toLowerCase().contains(q))
                || (s.host  != null && s.host.toLowerCase().contains(q))
                || (s.group != null && s.group.toLowerCase().contains(q));
            c.setVisible(match);
            ((GridData) c.getLayoutData()).exclude = !match;
        }
        listContainer.layout(true, true);
        if (scrolled != null && !scrolled.isDisposed() && innerComposite != null && !innerComposite.isDisposed()) {
            int cw = scrolled.getClientArea().width;
            scrolled.setMinSize(innerComposite.computeSize(cw > 0 ? cw : SWT.DEFAULT, SWT.DEFAULT));
        }
    }

    // -----------------------------------------------------------------------
    // Session actions
    // -----------------------------------------------------------------------
    private void newSession() {
        SessionDialog dlg = new SessionDialog(shell, "");
        SessionInfo saved = dlg.open();
        if (saved != null) {
            reload();
        }
    }

    private void editSession(SessionInfo session) {
        SessionDialog dlg = new SessionDialog(shell, "");
        dlg.setEditing(session);
        SessionInfo saved = dlg.open();
        if (saved != null) {
            reload();
        }
    }

    private void duplicateSession(SessionInfo source) {
        SessionInfo clone = new SessionInfo();
        clone.id            = java.util.UUID.randomUUID().toString();
        clone.name          = source.name.isBlank() ? "(copy)" : source.name + " (copy)";
        clone.host          = source.host;
        clone.port          = source.port;
        clone.username      = source.username;
        clone.keyPath       = source.keyPath;
        clone.group         = source.group;
        clone.credentialId  = source.credentialId;
        clone.authType      = source.authType;
        clone.appearFontName = source.appearFontName;
        clone.appearFontSize = source.appearFontSize;
        clone.appearFgR     = source.appearFgR;
        clone.appearFgG     = source.appearFgG;
        clone.appearFgB     = source.appearFgB;
        clone.appearBgR     = source.appearBgR;
        clone.appearBgG     = source.appearBgG;
        clone.appearBgB     = source.appearBgB;
        clone.logEnabled    = source.logEnabled;
        clone.logDir        = source.logDir;
        clone.logFileName   = source.logFileName;
        clone.terminalType  = source.terminalType;
        clone.backspaceCode = source.backspaceCode;
        SessionDialog dlg = new SessionDialog(shell, "");
        dlg.setEditing(clone);
        SessionInfo saved = dlg.open();
        if (saved != null) reload();
    }

    private void deleteSession(SessionInfo session) {
        MessageBox mb = new MessageBox(shell, SWT.ICON_QUESTION | SWT.YES | SWT.NO);
        mb.setText("Delete Session");
        mb.setMessage("Delete session \"" + session.name + "\"?");
        if (mb.open() == SWT.YES) {
            try {
                SessionStorage.delete(session);
            } catch (Exception ex) {
                MessageBox err = new MessageBox(shell, SWT.ICON_ERROR | SWT.OK);
                err.setText("Error");
                err.setMessage("Could not delete session: " + ex.getMessage());
                err.open();
            }
            reload();
        }
    }

    private void openImport() {
        ImportSessionsDialog dlg = new ImportSessionsDialog(shell);
        java.util.List<SessionInfo> imported = dlg.open();
        if (imported == null || imported.isEmpty()) return;
        for (SessionInfo s : imported) {
            try {
                br.com.capoeirassh.ssh.storage.SessionStorage.save(s);
            } catch (Exception ex) {
                MessageBox err = new MessageBox(shell, SWT.ICON_ERROR | SWT.OK);
                err.setText("Import error");
                err.setMessage("Could not save \"" + s.name + "\": " + ex.getMessage());
                err.open();
            }
        }
        reload();
    }

    // -----------------------------------------------------------------------
    // Unified Import / Export menu
    // -----------------------------------------------------------------------
    private void openImportExportMenu(Composite anchor) {
        Menu menu = new Menu(shell, SWT.POP_UP);

        MenuItem miExport = new MenuItem(menu, SWT.PUSH);
        miExport.setText("Export backup...");
        miExport.addListener(SWT.Selection, e -> openExport());

        new MenuItem(menu, SWT.SEPARATOR);

        MenuItem miLegacy = new MenuItem(menu, SWT.PUSH);
        miLegacy.setText("Import from PuTTY / MobaXterm...");
        miLegacy.addListener(SWT.Selection, e -> openImport());

        MenuItem miBackup = new MenuItem(menu, SWT.PUSH);
        miBackup.setText("Import from Capoeira backup...");
        miBackup.addListener(SWT.Selection, e -> openImportBackup());

        Point loc = anchor.toDisplay(anchor.getSize().x, anchor.getSize().y);
        menu.setLocation(loc);
        menu.setVisible(true);
    }

    // -----------------------------------------------------------------------
    // Export backup
    // -----------------------------------------------------------------------
    private void openExport() {
        Shell dlg = new Shell(shell, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM);
        dlg.setText("Export Capoeira Backup");
        AppIcon.apply(dlg);
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 16; gl.marginHeight = 12; gl.verticalSpacing = 8;
        dlg.setLayout(gl);

        bLabel(dlg, "Backup password:");
        Text txtPwd  = PasswordField.create(dlg, bFill());
        bLabel(dlg, "Confirm password:");
        Text txtPwd2 = PasswordField.create(dlg, bFill());

        new Label(dlg, SWT.NONE);
        Button chkVault = new Button(dlg, SWT.CHECK);
        boolean vaultUnlocked = br.com.capoeirassh.ssh.storage.CredentialStore.getInstance().isUnlocked();
        chkVault.setText(vaultUnlocked
                ? "Include credentials vault"
                : "Include credentials vault  (unlock vault first)");
        chkVault.setEnabled(vaultUnlocked);
        chkVault.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        new Label(dlg, SWT.NONE);
        Composite btns = new Composite(dlg, SWT.NONE);
        btns.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
        RowLayout rl = new RowLayout(SWT.HORIZONTAL); rl.spacing = 8;
        btns.setLayout(rl);
        Button btnExport = new Button(btns, SWT.PUSH); btnExport.setText("  Export…  ");
        Button btnCancel = new Button(btns, SWT.PUSH); btnCancel.setText("  Cancel  ");
        dlg.setDefaultButton(btnExport);

        btnCancel.addListener(SWT.Selection, e -> dlg.dispose());
        btnExport.addListener(SWT.Selection, e -> {
            char[] pw  = txtPwd.getTextChars();
            char[] pw2 = txtPwd2.getTextChars();
            if (pw.length == 0) {
                Arrays.fill(pw2, '\0');
                bAlert(dlg, "Please enter a backup password.");
                return;
            }
            if (!Arrays.equals(pw, pw2)) {
                Arrays.fill(pw, '\0'); Arrays.fill(pw2, '\0');
                bAlert(dlg, "Passwords do not match.");
                return;
            }
            Arrays.fill(pw2, '\0');

            FileDialog fd = new FileDialog(dlg, SWT.SAVE);
            fd.setText("Save Capoeira Backup");
            fd.setFilterExtensions(new String[]{ "*.capoeira-backup", "*.*" });
            fd.setFilterNames(new String[]{ "Capoeira backup (*.capoeira-backup)", "All files (*.*)" });
            fd.setFileName("capoeira-backup.capoeira-backup");
            fd.setOverwrite(true);
            String path = fd.open();
            if (path == null) { Arrays.fill(pw, '\0'); return; }

            try {
                BackupBundle.export(Path.of(path), pw, chkVault.getSelection());
                dlg.dispose();
                MessageBox ok = new MessageBox(shell, SWT.ICON_INFORMATION | SWT.OK);
                ok.setText("Export successful");
                ok.setMessage("Backup saved to:\n" + path);
                ok.open();
            } catch (Exception ex) {
                bAlert(dlg, "Export failed:\n" + ex.getMessage());
            } finally {
                Arrays.fill(pw, '\0');
            }
        });

        dlg.pack();
        dlg.setSize(Math.max(dlg.getSize().x, 380), dlg.getSize().y);
        Rectangle pb = shell.getBounds();
        Point     sz = dlg.getSize();
        dlg.setLocation(pb.x + (pb.width - sz.x) / 2, pb.y + (pb.height - sz.y) / 2);
        dlg.open();
        Display d = shell.getDisplay();
        while (!dlg.isDisposed()) { if (!d.readAndDispatch()) d.sleep(); }
    }

    // -----------------------------------------------------------------------
    // Import from Capoeira backup
    // -----------------------------------------------------------------------
    private void openImportBackup() {
        FileDialog fd = new FileDialog(shell, SWT.OPEN);
        fd.setText("Open Capoeira Backup");
        fd.setFilterExtensions(new String[]{ "*.capoeira-backup", "*.*" });
        fd.setFilterNames(new String[]{ "Capoeira backup (*.capoeira-backup)", "All files (*.*)" });
        String path = fd.open();
        if (path == null) return;

        // Password prompt
        Shell pwDlg = new Shell(shell, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM);
        pwDlg.setText("Import Backup – Password");
        AppIcon.apply(pwDlg);
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 16; gl.marginHeight = 12; gl.verticalSpacing = 8;
        pwDlg.setLayout(gl);

        bLabel(pwDlg, "Backup password:");
        Text txtPwd = PasswordField.create(pwDlg, bFill());

        new Label(pwDlg, SWT.NONE);
        Composite btns = new Composite(pwDlg, SWT.NONE);
        btns.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
        RowLayout rl = new RowLayout(SWT.HORIZONTAL); rl.spacing = 8;
        btns.setLayout(rl);
        Button btnOk     = new Button(btns, SWT.PUSH); btnOk.setText("  Import  ");
        Button btnCancel = new Button(btns, SWT.PUSH); btnCancel.setText("  Cancel  ");
        pwDlg.setDefaultButton(btnOk);

        btnCancel.addListener(SWT.Selection, e -> pwDlg.dispose());
        btnOk.addListener(SWT.Selection, e -> {
            char[] pw = txtPwd.getTextChars();
            BackupBundle.ImportResult result;
            try {
                result = BackupBundle.importBundle(Path.of(path), pw);
            } catch (Exception ex) {
                Arrays.fill(pw, '\0');
                bAlert(pwDlg, "Import failed:\n" + ex.getMessage());
                return;
            } finally {
                Arrays.fill(pw, '\0');
            }
            pwDlg.dispose();

            // Merge credentials (if any) and build credentialId remap
            java.util.Map<String, String> credRemap = java.util.Collections.emptyMap();
            int credImported = 0;
            if (!result.credentials().isEmpty()) {
                br.com.capoeirassh.ssh.storage.CredentialStore cs =
                        br.com.capoeirassh.ssh.storage.CredentialStore.getInstance();
                if (cs.isUnlocked()) {
                    try {
                        credRemap = cs.mergeCredentials(result.credentials());
                        credImported = credRemap.size();
                    } catch (Exception ex) {
                        bAlert(shell, "Could not merge credentials:\n" + ex.getMessage());
                    }
                } else {
                    bAlert(shell, result.credentials().size()
                        + " credential(s) in this backup were skipped because the vault is locked.\n"
                        + "Unlock your vault and import again to include them.");
                }
            }

            // Remap sessions' credentialId, then save
            final java.util.Map<String, String> remap = credRemap;
            int saved = 0, failed = 0;
            for (SessionInfo s : result.sessions()) {
                if (!s.credentialId.isEmpty() && remap.containsKey(s.credentialId))
                    s.credentialId = remap.get(s.credentialId);
                try { SessionStorage.save(s); saved++; }
                catch (Exception ex) { failed++; }
            }
            final int credCount = credImported;

            reload();

            StringBuilder msg = new StringBuilder();
            msg.append(saved).append(" session").append(saved == 1 ? "" : "s").append(" imported.");
            if (failed > 0) msg.append("\n").append(failed).append(" could not be saved.");
            if (credCount > 0) msg.append("\n").append(credCount).append(" credential").append(credCount == 1 ? "" : "s").append(" merged into vault.");
            MessageBox ok = new MessageBox(shell, SWT.ICON_INFORMATION | SWT.OK);
            ok.setText("Import complete"); ok.setMessage(msg.toString()); ok.open();
        });

        pwDlg.pack();
        pwDlg.setSize(Math.max(pwDlg.getSize().x, 320), pwDlg.getSize().y);
        Rectangle pb = shell.getBounds();
        Point     sz = pwDlg.getSize();
        pwDlg.setLocation(pb.x + (pb.width - sz.x) / 2, pb.y + (pb.height - sz.y) / 2);
        pwDlg.open();
        Display d = shell.getDisplay();
        while (!pwDlg.isDisposed()) { if (!d.readAndDispatch()) d.sleep(); }
    }

    // Small helpers for the dialog builders above
    private static void  bLabel(Composite p, String t) {
        Label l = new Label(p, SWT.NONE); l.setText(t);
        l.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
    }
    private static GridData bFill() { return new GridData(SWT.FILL, SWT.CENTER, true, false); }
    private static void bAlert(Shell parent, String msg) {
        MessageBox mb = new MessageBox(parent, SWT.ICON_WARNING | SWT.OK);
        mb.setMessage(msg); mb.open();
    }

    private void openSettings() {
        ConfigurationSettingsDialog dlg = new ConfigurationSettingsDialog(
            shell, "Configuration Setting",
            br.com.capoeirassh.ssh.storage.SessionDefaults.get());
        if (dlg.open()) {
            br.com.capoeirassh.ssh.storage.SessionDefaults.set(dlg.getResult());
        }
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------
    public CTabItem getTabItem() { return tabItem; }

    /** Focus the tab's default control — called whenever this tab becomes the active one,
     *  so keyboard input always goes somewhere sensible on this tab rather than lingering
     *  on a control from whatever tab was previously focused. */
    public void focusDefault() {
        if (searchText != null && !searchText.isDisposed()) searchText.setFocus();
    }

    public void reload(Runnable ignored) { reload(); }

    public void notifyUpdateAvailable(String version) {
        if (aboutIconBox == null || aboutIconBox.isDisposed()) return;
        updateAvailable = true;
        updateVersion   = version;
        aboutIconBox.setToolTipText("Update available: v" + version + " — click About to download");
        for (Control c : aboutIconBox.getChildren())
            c.setToolTipText(aboutIconBox.getToolTipText());
        aboutIconBox.redraw();
    }
}
