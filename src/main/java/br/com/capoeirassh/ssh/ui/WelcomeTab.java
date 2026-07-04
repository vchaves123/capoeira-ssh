package br.com.capoeirassh.ssh.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

import br.com.capoeirassh.ssh.BuildInfo;

public class WelcomeTab {

    // Capoeira palette
    private static final int[] C_BG     = {15,  15,  15};   // Breu     #0f0f0f
    private static final int[] C_GOLD   = {232, 184, 75};   // Ouro     #E8B84B
    private static final int[] C_TERRA  = {192, 94,  26};   // Terracota #C05E1A
    private static final int[] C_AREIA  = {240, 237, 230};  // Areia    #f0ede6
    private static final int[] C_DIM    = {106, 98,  88};   // warm grey

    private final CTabItem tabItem;

    public WelcomeTab(CTabFolder folder, Runnable onNewSession) {
        tabItem = new CTabItem(folder, SWT.NONE);
        tabItem.setText("Welcome");

        Display display = folder.getDisplay();

        Color bg    = new Color(display, C_BG[0],    C_BG[1],    C_BG[2]);
        Color gold  = new Color(display, C_GOLD[0],  C_GOLD[1],  C_GOLD[2]);
        Color terra = new Color(display, C_TERRA[0], C_TERRA[1], C_TERRA[2]);
        Color areia = new Color(display, C_AREIA[0], C_AREIA[1], C_AREIA[2]);
        Color dim   = new Color(display, C_DIM[0],   C_DIM[1],   C_DIM[2]);

        Composite root = new Composite(folder, SWT.NONE);
        root.setBackground(bg);
        root.setLayout(new GridLayout(1, false));
        root.addDisposeListener(e -> {
            bg.dispose(); gold.dispose(); terra.dispose(); areia.dispose(); dim.dispose();
        });

        spacer(root, bg, 40);

        // Title — Ouro
        Label title = new Label(root, SWT.CENTER);
        title.setText("Capoeira SSH Client");
        title.setBackground(bg);
        title.setForeground(gold);
        title.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        Font titleFont = new Font(display, "Consolas", 22, SWT.BOLD);
        title.setFont(titleFont);
        title.addDisposeListener(e -> titleFont.dispose());

        spacer(root, bg, 10);

        // Subtitle — Terracota
        Label sub = new Label(root, SWT.CENTER);
        sub.setText("xterm-256color terminal emulator");
        sub.setBackground(bg);
        sub.setForeground(terra);
        sub.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        Font subFont = new Font(display, "Consolas", 11, SWT.NORMAL);
        sub.setFont(subFont);
        sub.addDisposeListener(e -> subFont.dispose());

        spacer(root, bg, 40);

        // Instructions
        String[] lines = {
            "Getting started",
            "",
            "  1.  Use the session tree on the left to manage connections.",
            "  2.  Right-click the tree to create a New Session or a New Group.",
            "  3.  Double-click a saved session (or press Enter) to connect.",
            "  4.  Each connection opens a new terminal tab above.",
            "",
            "  Keyboard shortcuts",
            "  ──────────────────────────────────────────",
            "  Ctrl + N   New session",
            "  Ctrl + W   Close current tab",
            "  Ctrl + Tab / Ctrl + Shift + Tab   Switch tabs",
        };

        Font monoFont = new Font(display, "Consolas", 11, SWT.NORMAL);

        for (String line : lines) {
            Label lbl = new Label(root, SWT.NONE);
            lbl.setText(line);
            lbl.setBackground(bg);
            boolean isHeading = !line.isBlank() && !line.startsWith(" ") && !line.startsWith("─");
            lbl.setForeground(isHeading ? gold : areia);
            lbl.setFont(monoFont);
            lbl.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        }
        root.addDisposeListener(e -> monoFont.dispose());

        spacer(root, bg, 32);

        // Filler
        Label filler = new Label(root, SWT.NONE);
        filler.setBackground(bg);
        filler.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, true));

        // Version — dim warm grey
        Label lblVersion = new Label(root, SWT.RIGHT);
        lblVersion.setText("v" + BuildInfo.VERSION + "  build #" + BuildInfo.BUILD + "  —  " + BuildInfo.DATE);
        lblVersion.setBackground(bg);
        lblVersion.setForeground(dim);
        Font versionFont = new Font(display, "Consolas", 10, SWT.NORMAL);
        lblVersion.setFont(versionFont);
        GridData gdVer = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gdVer.verticalIndent = 4;
        lblVersion.setLayoutData(gdVer);
        lblVersion.addDisposeListener(e -> versionFont.dispose());

        tabItem.setControl(root);
    }

    public CTabItem getTabItem() { return tabItem; }

    private static void spacer(Composite parent, Color bg, int height) {
        Label gap = new Label(parent, SWT.NONE);
        gap.setBackground(bg);
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.heightHint = height;
        gap.setLayoutData(gd);
    }
}
