package br.com.capoeirassh.ssh.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

import br.com.capoeirassh.ssh.BuildInfo;

public class WelcomeTab {

    private final CTabItem tabItem;

    public WelcomeTab(CTabFolder folder, Runnable onNewSession) {
        tabItem = new CTabItem(folder, SWT.NONE);
        tabItem.setText("Welcome");

        Display display = folder.getDisplay();

        Composite root = new Composite(folder, SWT.NONE);
        root.setBackground(display.getSystemColor(SWT.COLOR_BLACK));
        root.setLayout(new GridLayout(1, false));

        // Vertical spacer
        spacer(root, 40);

        // Title
        Label title = new Label(root, SWT.CENTER);
        title.setText("Capoeira SSH Client");
        title.setBackground(display.getSystemColor(SWT.COLOR_BLACK));
        title.setForeground(new Color(display, 102, 204, 255));
        GridData gdTitle = new GridData(SWT.CENTER, SWT.CENTER, true, false);
        title.setLayoutData(gdTitle);
        Font titleFont = new Font(display, "Consolas", 22, SWT.BOLD);
        title.setFont(titleFont);
        title.addDisposeListener(e -> titleFont.dispose());

        spacer(root, 16);

        // Subtitle
        Label sub = new Label(root, SWT.CENTER);
        sub.setText("xterm-256color terminal emulator  —  build #" + BuildInfo.BUILD);
        sub.setBackground(display.getSystemColor(SWT.COLOR_BLACK));
        sub.setForeground(new Color(display, 140, 140, 140));
        sub.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        Font subFont = new Font(display, "Consolas", 11, SWT.NORMAL);
        sub.setFont(subFont);
        sub.addDisposeListener(e -> subFont.dispose());

        spacer(root, 40);

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
            lbl.setBackground(display.getSystemColor(SWT.COLOR_BLACK));
            boolean isHeading = !line.isBlank() && !line.startsWith(" ") && !line.startsWith("─");
            lbl.setForeground(isHeading
                ? new Color(display, 102, 204, 255)
                : new Color(display, 204, 204, 204));
            lbl.setFont(monoFont);
            lbl.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        }
        root.addDisposeListener(e -> monoFont.dispose());

        spacer(root, 32);

        // Filler to push version label to the bottom
        Label filler = new Label(root, SWT.NONE);
        filler.setBackground(display.getSystemColor(SWT.COLOR_BLACK));
        filler.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, true));

        // Version label — bottom-right
        Label lblVersion = new Label(root, SWT.RIGHT);
        lblVersion.setText("v" + BuildInfo.VERSION + "  build #" + BuildInfo.BUILD + "  —  " + BuildInfo.DATE);
        lblVersion.setBackground(display.getSystemColor(SWT.COLOR_BLACK));
        lblVersion.setForeground(new Color(display, 120, 120, 120));
        Font versionFont = new Font(display, "Consolas", 10, SWT.NORMAL);
        lblVersion.setFont(versionFont);
        GridData gdVer = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gdVer.horizontalIndent = 0;
        gdVer.verticalIndent = 4;
        lblVersion.setLayoutData(gdVer);
        lblVersion.addDisposeListener(e -> versionFont.dispose());

        tabItem.setControl(root);
    }

    public CTabItem getTabItem() { return tabItem; }

private static void spacer(Composite parent, int height) {
        Label gap = new Label(parent, SWT.NONE);
        gap.setBackground(parent.getDisplay().getSystemColor(SWT.COLOR_BLACK));
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.heightHint = height;
        gap.setLayoutData(gd);
    }
}
