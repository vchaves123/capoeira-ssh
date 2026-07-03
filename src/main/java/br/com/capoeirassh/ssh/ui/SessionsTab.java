package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.BuildInfo;
import br.com.capoeirassh.ssh.UpdateChecker;
import br.com.capoeirassh.ssh.model.SessionInfo;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.*;

import java.util.function.BiConsumer;

/**
 * The permanent "Sessions" tab.
 *
 * Layout (SashForm inside the tab):
 *   ┌────────────────────┬─────────────────────────────────┐
 *   │  Session tree      │  Welcome / quick-start info     │
 *   │  (tree panel)      │                                 │
 *   └────────────────────┴─────────────────────────────────┘
 */
public class SessionsTab {

    private final CTabItem        tabItem;
    private final SessionTreePanel treePanel;

    public SessionsTab(CTabFolder folder, Shell shell,
                       BiConsumer<SessionInfo, char[]> onConnect,
                       Runnable onCredentials, Runnable onAbout) {

        tabItem = new CTabItem(folder, SWT.NONE);   // no close button
        tabItem.setText("  Home  ");   // extra padding either side of the label

        Display display = folder.getDisplay();

        SashForm sash = new SashForm(folder, SWT.HORIZONTAL);

        // ── Left: session tree ───────────────────────────────────────────
        treePanel = new SessionTreePanel(sash, shell, onConnect);

        // ── Right: welcome / info pane ───────────────────────────────────
        Composite info = new Composite(sash, SWT.NONE);
        info.setBackground(display.getSystemColor(SWT.COLOR_BLACK));
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 40; gl.marginHeight = 40;
        info.setLayout(gl);

        spacer(info, 20);

        Label title = new Label(info, SWT.CENTER);
        title.setText("Capoeira SSH Client");
        title.setBackground(display.getSystemColor(SWT.COLOR_BLACK));
        title.setForeground(new Color(display, 102, 204, 255));
        title.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        Font titleFont = new Font(display, "Consolas", 22, SWT.BOLD);
        title.setFont(titleFont);
        title.addDisposeListener(e -> titleFont.dispose());

        spacer(info, 10);

        Label sub = new Label(info, SWT.CENTER);
        sub.setText("xterm-256color terminal emulator");
        sub.setBackground(display.getSystemColor(SWT.COLOR_BLACK));
        sub.setForeground(new Color(display, 140, 140, 140));
        sub.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        Font subFont = new Font(display, "Consolas", 11, SWT.NORMAL);
        sub.setFont(subFont);
        sub.addDisposeListener(e -> subFont.dispose());

        spacer(info, 30);

        String[] lines = {
            "Getting started",
            "",
            "  1.  Use the session tree on the left to manage connections.",
            "  2.  Right-click the tree to create a New Session or a New Group.",
            "  3.  Double-click a saved session (or press Enter) to connect.",
            "  4.  Each connection opens a new terminal tab.",
            "",
            "  Keyboard shortcuts",
            "  ──────────────────────────────────────────",
            "  Ctrl + N   New session",
            "  Ctrl + W   Close current tab",
            "  Ctrl + Tab / Ctrl + Shift + Tab   Switch tabs",
        };

        Font monoFont = new Font(display, "Consolas", 11, SWT.NORMAL);
        for (String line : lines) {
            Label lbl = new Label(info, SWT.NONE);
            lbl.setText(line);
            lbl.setBackground(display.getSystemColor(SWT.COLOR_BLACK));
            boolean isHeading = !line.isBlank() && !line.startsWith(" ") && !line.startsWith("─");
            lbl.setForeground(isHeading
                ? new Color(display, 102, 204, 255)
                : new Color(display, 204, 204, 204));
            lbl.setFont(monoFont);
            lbl.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        }
        info.addDisposeListener(e -> monoFont.dispose());

        spacer(info, 24);

        Composite btnRow = new Composite(info, SWT.NONE);
        btnRow.setBackground(display.getSystemColor(SWT.COLOR_BLACK));
        btnRow.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, false));
        RowLayout rl = new RowLayout(SWT.HORIZONTAL);
        rl.spacing = 12; rl.marginWidth = 0; rl.marginHeight = 0;
        btnRow.setLayout(rl);

        Button btnCreds    = new Button(btnRow, SWT.PUSH); btnCreds.setText("  Credential Manager  ");
        Button btnConfig   = new Button(btnRow, SWT.PUSH); btnConfig.setText("  Configuration Setting  ");
        Button btnAbout    = new Button(btnRow, SWT.PUSH); btnAbout.setText("  About  ");

        btnCreds.addListener(SWT.Selection,  e -> onCredentials.run());
        btnAbout.addListener(SWT.Selection,  e -> onAbout.run());
        btnConfig.addListener(SWT.Selection, e -> {
            ConfigurationSettingsDialog dlg = new ConfigurationSettingsDialog(
                shell, "Configuration Setting", br.com.capoeirassh.ssh.storage.SessionDefaults.get());
            if (dlg.open()) {
                br.com.capoeirassh.ssh.storage.SessionDefaults.set(dlg.getResult());
            }
        });

        // Filler pushes version label to the bottom
        Label filler = new Label(info, SWT.NONE);
        filler.setBackground(display.getSystemColor(SWT.COLOR_BLACK));
        filler.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, true));

        // Build version — bottom-right, subtle; links to this release on GitHub
        String releaseUrl = "https://github.com/vchaves123/capoeira-ssh/releases/tag/v" + BuildInfo.VERSION;
        Link lblVersion = new Link(info, SWT.RIGHT);
        lblVersion.setText("<a href=\"" + releaseUrl + "\">v" + BuildInfo.VERSION + "  build #" + BuildInfo.BUILD + "  —  " + BuildInfo.DATE + "</a>");
        lblVersion.setBackground(display.getSystemColor(SWT.COLOR_BLACK));
        lblVersion.setForeground(new Color(display, 60, 60, 60));
        Font vFont = new Font(display, "Consolas", 9, SWT.NORMAL);
        lblVersion.setFont(vFont);
        lblVersion.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        lblVersion.addDisposeListener(e -> vFont.dispose());
        lblVersion.addListener(SWT.Selection, e -> Program.launch(e.text));

        // Update notice — hidden until a newer release is found on GitHub
        Link lblUpdate = new Link(info, SWT.RIGHT);
        lblUpdate.setBackground(display.getSystemColor(SWT.COLOR_BLACK));
        lblUpdate.setForeground(new Color(display, 102, 204, 255));
        Font updFont = new Font(display, "Consolas", 9, SWT.NORMAL);
        lblUpdate.setFont(updFont);
        GridData gdUpdate = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gdUpdate.exclude = true;
        lblUpdate.setLayoutData(gdUpdate);
        lblUpdate.setVisible(false);
        lblUpdate.addDisposeListener(e -> updFont.dispose());
        lblUpdate.addListener(SWT.Selection, e -> Program.launch(UpdateChecker.RELEASES_URL));

        UpdateChecker.checkAsync(newVersion -> display.asyncExec(() -> {
            if (lblUpdate.isDisposed()) return;
            lblUpdate.setText("New version v" + newVersion + " available — <a href=\"download\">Download</a>");
            gdUpdate.exclude = false;
            lblUpdate.setVisible(true);
            lblUpdate.getParent().layout(true, true);
        }));

        sash.setWeights(new int[]{ 28, 72 });
        tabItem.setControl(sash);
    }

    public CTabItem        getTabItem()   { return tabItem;   }
    public SessionTreePanel getTreePanel() { return treePanel; }
    public void            reload()       { treePanel.reload(); }



    private static void spacer(Composite parent, int height) {
        Label gap = new Label(parent, SWT.NONE);
        gap.setBackground(parent.getDisplay().getSystemColor(SWT.COLOR_BLACK));
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.heightHint = height;
        gap.setLayoutData(gd);
    }
}
