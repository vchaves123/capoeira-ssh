package br.com.capoeirassh.ssh.storage;

import br.com.capoeirassh.ssh.model.ConfigurationSettings;
import org.eclipse.swt.graphics.RGB;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Global default logging / appearance / terminal-type / backspace-key settings,
 * used to pre-fill new sessions. Edited via the "Configuration Setting" dialog
 * reachable from the Home tab.
 */
public final class SessionDefaults {

    private static final Path FILE = Path.of(
            System.getProperty("user.home"), ".capoeira", "session-defaults.properties");

    /** Schema version of session-defaults.properties, written by save() below. Absent on every
     *  file written before this field existed — load() treats a missing value as compatible, but
     *  refuses (keeps the built-in ConfigurationSettings defaults) a value greater than
     *  SCHEMA_VERSION, i.e. a file written by a future version of this program in a format this
     *  build doesn't understand — see the identical fix in SessionStorage/AppearanceSettings for
     *  the full rationale. */
    private static final int SCHEMA_VERSION = 1;

    private static ConfigurationSettings current = new ConfigurationSettings();

    static { load(); }

    private SessionDefaults() {}

    public static ConfigurationSettings get() {
        return current.copy();
    }

    public static void set(ConfigurationSettings settings) {
        current = settings.copy();
        save();
        // Keep the legacy global appearance store (used by TerminalAppearanceDialog's
        // "Reset to defaults" and TerminalTab's fallback) in sync.
        AppearanceSettings.set(
            current.appearFontName.isBlank() ? AppearanceSettings.getFontName() : current.appearFontName,
            current.appearFontSize > 0 ? current.appearFontSize : AppearanceSettings.getFontSize(),
            new RGB(current.appearFgR, current.appearFgG, current.appearFgB),
            new RGB(current.appearBgR, current.appearBgG, current.appearBgB));
    }

    private static void load() {
        if (!Files.exists(FILE)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(FILE)) {
            p.load(in);
        } catch (Exception ignored) {
            return;
        }

        // A file with no schemaVersion is one written before this field existed — treated
        // as compatible. A present value greater than SCHEMA_VERSION means a future version
        // wrote it in a format this build doesn't understand; refuse it (keep the current
        // in-memory defaults) rather than silently applying whatever partial/wrong values
        // result from keys this build doesn't recognize. An unparseable version string is
        // treated as compatible too, same rationale as AppearanceSettings.
        String verStr = p.getProperty("schemaVersion");
        if (verStr != null && parseIntOr(verStr, SCHEMA_VERSION) > SCHEMA_VERSION) return;

        // Each field falls back to its own default independently, so one corrupted value no
        // longer discards every other still-valid field in the file, unlike the single
        // all-or-nothing try/catch this replaced.
        ConfigurationSettings c = new ConfigurationSettings();
        c.appearFontSize = parseIntOr(p.getProperty("appearFontSize", "0"), 0);
        c.appearFontName = p.getProperty("appearFontName", "");
        c.appearFgR = parseIntOr(p.getProperty("appearFgR", "204"), 204);
        c.appearFgG = parseIntOr(p.getProperty("appearFgG", "204"), 204);
        c.appearFgB = parseIntOr(p.getProperty("appearFgB", "204"), 204);
        c.appearBgR = parseIntOr(p.getProperty("appearBgR", "0"), 0);
        c.appearBgG = parseIntOr(p.getProperty("appearBgG", "0"), 0);
        c.appearBgB = parseIntOr(p.getProperty("appearBgB", "0"), 0);
        c.logEnabled  = Boolean.parseBoolean(p.getProperty("logEnabled", "false"));
        c.logDir      = p.getProperty("logDir", "");
        c.logFileName = p.getProperty("logFileName", "");
        c.terminalType  = p.getProperty("terminalType", "xterm-256color");
        c.backspaceCode = parseIntOr(p.getProperty("backspaceCode", "127"), 127);
        // Match SessionStorage's clamp: only DEL (0x7F) or BS (0x08) are valid; anything
        // else would be narrowed to an arbitrary byte and sent to the SSH server.
        if (c.backspaceCode != 0x08 && c.backspaceCode != 0x7F) c.backspaceCode = 0x7F;
        c.sshVerbose = Boolean.parseBoolean(p.getProperty("sshVerbose", "false"));
        c.allowColumnMode = Boolean.parseBoolean(p.getProperty("allowColumnMode", "true"));
        current = c;
    }

    private static int parseIntOr(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static void save() {
        Properties p = new Properties();
        p.setProperty("schemaVersion", String.valueOf(SCHEMA_VERSION));
        p.setProperty("appearFontSize", String.valueOf(current.appearFontSize));
        p.setProperty("appearFontName", current.appearFontName);
        p.setProperty("appearFgR", String.valueOf(current.appearFgR));
        p.setProperty("appearFgG", String.valueOf(current.appearFgG));
        p.setProperty("appearFgB", String.valueOf(current.appearFgB));
        p.setProperty("appearBgR", String.valueOf(current.appearBgR));
        p.setProperty("appearBgG", String.valueOf(current.appearBgG));
        p.setProperty("appearBgB", String.valueOf(current.appearBgB));
        p.setProperty("logEnabled", String.valueOf(current.logEnabled));
        p.setProperty("logDir", current.logDir);
        p.setProperty("logFileName", current.logFileName);
        p.setProperty("terminalType", current.terminalType);
        p.setProperty("backspaceCode", String.valueOf(current.backspaceCode));
        p.setProperty("sshVerbose", String.valueOf(current.sshVerbose));
        p.setProperty("allowColumnMode", String.valueOf(current.allowColumnMode));
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            p.store(baos, null);
            SecureFiles.write(FILE, baos.toByteArray());
        } catch (IOException ignored) {}
    }
}
