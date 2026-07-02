package br.com.quatorzebis.ssh.ui;

import br.com.quatorzebis.ssh.model.ConfigurationSettings;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

/**
 * "Configuration Setting" dialog: logging, appearance, terminal type and backspace key.
 * Reused in three scopes — global defaults (Home tab), a single session
 * (Session dialog), and a single running terminal tab — the caller decides
 * what the returned {@link ConfigurationSettings} is applied to.
 */
public class ConfigurationSettingsDialog {

    private final Shell  parent;
    private final String title;
    private final String hostHint;
    private final ConfigurationSettings settings;
    private boolean confirmed = false;

    public ConfigurationSettingsDialog(Shell parent, String title, ConfigurationSettings initial) {
        this(parent, title, initial, "");
    }

    /** @param hostHint session host, used as the log file's base name when no custom name is
     *                  typed (matches what TerminalTab actually writes); "" when not applicable
     *                  (e.g. editing global defaults). */
    public ConfigurationSettingsDialog(Shell parent, String title, ConfigurationSettings initial, String hostHint) {
        this.parent   = parent;
        this.title    = title;
        this.settings = initial.copy();
        this.hostHint = hostHint != null ? hostHint : "";
    }

    /** Returns true if the user pressed OK; {@link #getResult()} then holds the new values. */
    public boolean open() {
        Shell dlg = new Shell(parent, SWT.APPLICATION_MODAL | SWT.DIALOG_TRIM | SWT.RESIZE);
        dlg.setText(title);
        AppIcon.apply(dlg);
        center(dlg, parent);

        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 16; gl.marginHeight = 12; gl.verticalSpacing = 7;
        dlg.setLayout(gl);

        // ── Logging ───────────────────────────────────────────────────────────
        label(dlg, "Log output:");
        Composite cmpLog = new Composite(dlg, SWT.NONE);
        cmpLog.setLayoutData(fill());
        GridLayout glLog = new GridLayout(3, false);
        glLog.marginWidth = 0; glLog.marginHeight = 0; glLog.horizontalSpacing = 6;
        cmpLog.setLayout(glLog);

        Button chkLog = new Button(cmpLog, SWT.CHECK);
        chkLog.setText("Enable");

        Text txtLogDir = new Text(cmpLog, SWT.BORDER | SWT.READ_ONLY);
        txtLogDir.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        txtLogDir.setToolTipText("Directory where log files will be saved");

        Button btnLogBrowse = new Button(cmpLog, SWT.PUSH);
        btnLogBrowse.setText("…");

        String defaultLogDir = System.getProperty("user.home") + "/.14bis/screen_captures";
        txtLogDir.setText(settings.logDir.isBlank() ? defaultLogDir : settings.logDir);

        chkLog.addListener(SWT.Selection, e -> {
            boolean on = chkLog.getSelection();
            txtLogDir.setEnabled(on);
            btnLogBrowse.setEnabled(on);
        });

        btnLogBrowse.addListener(SWT.Selection, e -> {
            DirectoryDialog dd = new DirectoryDialog(dlg, SWT.NONE);
            dd.setText("Select log directory");
            dd.setFilterPath(txtLogDir.getText());
            String chosen = dd.open();
            if (chosen != null) txtLogDir.setText(chosen);
        });

        label(dlg, "");
        Composite cmpLogFile = new Composite(dlg, SWT.NONE);
        cmpLogFile.setLayoutData(fill());
        GridLayout glLogFile = new GridLayout(2, false);
        glLogFile.marginWidth = 0; glLogFile.marginHeight = 0; glLogFile.horizontalSpacing = 6;
        cmpLogFile.setLayout(glLogFile);
        Label lblLogFile = new Label(cmpLogFile, SWT.NONE);
        lblLogFile.setText("File name:");
        Text txtLogFileName = new Text(cmpLogFile, SWT.BORDER);
        txtLogFileName.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        txtLogFileName.setMessage("e.g. session.log");
        txtLogFileName.setText(!settings.logFileName.isBlank()
            ? defaultLogFileName(settings.logFileName) : defaultLogFileName(null));
        chkLog.addListener(SWT.Selection, e -> txtLogFileName.setEnabled(chkLog.getSelection()));

        chkLog.setSelection(settings.logEnabled);
        txtLogDir.setEnabled(settings.logEnabled);
        btnLogBrowse.setEnabled(settings.logEnabled);
        txtLogFileName.setEnabled(settings.logEnabled);

        // ── Appearance ────────────────────────────────────────────────────────
        label(dlg, "Appearance:");
        Composite cmpAppear = new Composite(dlg, SWT.NONE);
        cmpAppear.setLayoutData(fill());
        RowLayout rlApp = new RowLayout(SWT.HORIZONTAL);
        rlApp.spacing = 8; rlApp.marginWidth = 0; rlApp.marginHeight = 0; rlApp.center = true;
        cmpAppear.setLayout(rlApp);

        int[]    appFontSize = { settings.appearFontSize };
        String[] appFontName = { settings.appearFontName != null ? settings.appearFontName : "" };
        int[]    appFg = { settings.appearFgR, settings.appearFgG, settings.appearFgB };
        int[]    appBg = { settings.appearBgR, settings.appearBgG, settings.appearBgB };

        Label swatchFg = new Label(cmpAppear, SWT.BORDER);
        swatchFg.setLayoutData(new RowData(18, 18));
        Label swatchBg = new Label(cmpAppear, SWT.BORDER);
        swatchBg.setLayoutData(new RowData(18, 18));
        Label lblAppearInfo = new Label(cmpAppear, SWT.NONE);

        Runnable refreshAppear = () -> {
            Display d = dlg.getDisplay();
            Color pf = swatchFg.getBackground();
            swatchFg.setBackground(new Color(d, appFg[0], appFg[1], appFg[2]));
            if (pf != null && !pf.isDisposed() && !pf.equals(d.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND))) pf.dispose();
            Color pb = swatchBg.getBackground();
            swatchBg.setBackground(new Color(d, appBg[0], appBg[1], appBg[2]));
            if (pb != null && !pb.isDisposed() && !pb.equals(d.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND))) pb.dispose();
            lblAppearInfo.setText("  " + (appFontSize[0] > 0 ? appFontSize[0] : 14) + "pt");
            cmpAppear.layout();
        };
        refreshAppear.run();

        // SWT never auto-disposes application-created Colors; refreshAppear only frees the
        // PREVIOUS swatch background, so free the final pair when the dialog closes.
        dlg.addListener(SWT.Dispose, ev -> {
            Display d = dlg.getDisplay();
            for (Label sw : new Label[]{ swatchFg, swatchBg }) {
                Color c = sw.getBackground();
                if (c != null && !c.isDisposed() && !c.equals(d.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND)))
                    c.dispose();
            }
        });

        Button btnAppear = new Button(cmpAppear, SWT.PUSH);
        btnAppear.setText("Customise…");
        btnAppear.moveAbove(swatchFg);
        btnAppear.addListener(SWT.Selection, e -> {
            int is = appFontSize[0] > 0 ? appFontSize[0] : 14;
            String fn = appFontName[0].isBlank() ? br.com.quatorzebis.ssh.storage.AppearanceSettings.getFontName() : appFontName[0];
            RGB fg0 = new RGB(appFg[0], appFg[1], appFg[2]);
            RGB bg0 = new RGB(appBg[0], appBg[1], appBg[2]);
            TerminalAppearanceDialog tad = new TerminalAppearanceDialog(dlg, fn, is, fg0, bg0);
            if (tad.open()) {
                appFontName[0] = tad.getChosenFontName();
                appFontSize[0] = tad.getChosenFontSize();
                RGB fg2 = tad.getChosenFgColor();
                RGB bg2 = tad.getChosenBgColor();
                appFg[0] = fg2.red; appFg[1] = fg2.green; appFg[2] = fg2.blue;
                appBg[0] = bg2.red; appBg[1] = bg2.green; appBg[2] = bg2.blue;
                refreshAppear.run();
            }
        });

        // ── Terminal type / Backspace key ────────────────────────────────────
        label(dlg, "Terminal type:");
        Combo cmbTermType = new Combo(dlg, SWT.DROP_DOWN);
        cmbTermType.setLayoutData(fill());
        cmbTermType.setItems("xterm-256color", "xterm", "vt100", "ansi", "linux");
        cmbTermType.setText(settings.terminalType != null && !settings.terminalType.isBlank()
            ? settings.terminalType : "xterm-256color");

        label(dlg, "Backspace key:");
        Combo cmbBackspace = new Combo(dlg, SWT.DROP_DOWN | SWT.READ_ONLY);
        cmbBackspace.setLayoutData(fill());
        cmbBackspace.setItems("DEL (0x7F) — Linux and most systems", "BS (0x08) — AIX");
        cmbBackspace.select(settings.backspaceCode == 0x08 ? 1 : 0);

        // ── Buttons ───────────────────────────────────────────────────────────
        new Label(dlg, SWT.NONE);
        Composite cmpBtns = new Composite(dlg, SWT.NONE);
        cmpBtns.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
        RowLayout rlBtn = new RowLayout(SWT.HORIZONTAL); rlBtn.spacing = 8;
        cmpBtns.setLayout(rlBtn);
        Button btnOk     = new Button(cmpBtns, SWT.PUSH); btnOk.setText("OK");
        Button btnCancel = new Button(cmpBtns, SWT.PUSH); btnCancel.setText("Cancel");
        dlg.setDefaultButton(btnOk);

        btnCancel.addListener(SWT.Selection, e -> dlg.dispose());
        btnOk.addListener(SWT.Selection, e -> {
            settings.logEnabled  = chkLog.getSelection();
            settings.logDir      = txtLogDir.getText().trim();
            settings.logFileName = stripToBaseName(txtLogFileName.getText().trim());

            settings.appearFontSize = appFontSize[0];
            settings.appearFontName = appFontName[0];
            settings.appearFgR = appFg[0]; settings.appearFgG = appFg[1]; settings.appearFgB = appFg[2];
            settings.appearBgR = appBg[0]; settings.appearBgG = appBg[1]; settings.appearBgB = appBg[2];

            String termType = cmbTermType.getText().trim();
            settings.terminalType  = termType.isEmpty() ? "xterm-256color" : termType;
            settings.backspaceCode = cmbBackspace.getSelectionIndex() == 1 ? 0x08 : 0x7F;

            confirmed = true;
            dlg.dispose();
        });

        dlg.pack();
        dlg.setSize(Math.max(dlg.getSize().x, 420), dlg.getSize().y);
        center(dlg, parent);

        dlg.open();
        Display display = parent.getDisplay();
        while (!dlg.isDisposed()) { if (!display.readAndDispatch()) display.sleep(); }
        return confirmed;
    }

    public ConfigurationSettings getResult() { return settings; }

    private static final java.util.regex.Pattern TIMESTAMPED_LOG_NAME =
        java.util.regex.Pattern.compile("^\\d{8}_\\d{6}_(.+)\\.log$");

    /** Builds the full "<timestamp>_<base>.log" name shown in the field, so the user sees
     *  exactly the format {@code TerminalTab} will write. {@code base} overrides the default
     *  (session host, or "session"); a stale timestamp already in it is stripped first. */
    private String defaultLogFileName(String base) {
        String b = base != null && !base.isBlank() ? base : (!hostHint.isBlank() ? hostHint : "session");
        java.util.regex.Matcher m = TIMESTAMPED_LOG_NAME.matcher(b);
        if (m.matches()) b = m.group(1);
        String ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return ts + "_" + b.replaceAll("[^\\w\\-.]", "_") + ".log";
    }

    /** Extracts the base name TerminalTab should re-timestamp at actual log-open time —
     *  strips an illustrative "<timestamp>_" prefix and ".log" suffix if the user left the
     *  field showing the auto-generated full name unedited. */
    private static String stripToBaseName(String fileName) {
        java.util.regex.Matcher m = TIMESTAMPED_LOG_NAME.matcher(fileName);
        return m.matches() ? m.group(1) : fileName;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private static Label label(Composite p, String text) {
        Label l = new Label(p, SWT.NONE); l.setText(text);
        l.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        return l;
    }
    private static GridData fill() { return new GridData(SWT.FILL, SWT.CENTER, true, false); }
    private static void center(Shell child, Shell parent) {
        Rectangle rp = parent.getBounds(); Rectangle rc = child.getBounds();
        child.setLocation(rp.x + (rp.width - rc.width) / 2, rp.y + (rp.height - rc.height) / 2);
    }
}
