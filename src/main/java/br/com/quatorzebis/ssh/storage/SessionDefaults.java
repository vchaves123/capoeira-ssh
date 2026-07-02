package br.com.quatorzebis.ssh.storage;

import br.com.quatorzebis.ssh.model.ConfigurationSettings;
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
            System.getProperty("user.home"), ".14bis", "session-defaults.properties");

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
            ConfigurationSettings c = new ConfigurationSettings();
            c.appearFontSize = Integer.parseInt(p.getProperty("appearFontSize", "0"));
            c.appearFontName = p.getProperty("appearFontName", "");
            c.appearFgR = Integer.parseInt(p.getProperty("appearFgR", "204"));
            c.appearFgG = Integer.parseInt(p.getProperty("appearFgG", "204"));
            c.appearFgB = Integer.parseInt(p.getProperty("appearFgB", "204"));
            c.appearBgR = Integer.parseInt(p.getProperty("appearBgR", "0"));
            c.appearBgG = Integer.parseInt(p.getProperty("appearBgG", "0"));
            c.appearBgB = Integer.parseInt(p.getProperty("appearBgB", "0"));
            c.logEnabled  = Boolean.parseBoolean(p.getProperty("logEnabled", "false"));
            c.logDir      = p.getProperty("logDir", "");
            c.logFileName = p.getProperty("logFileName", "");
            c.terminalType  = p.getProperty("terminalType", "xterm-256color");
            c.backspaceCode = Integer.parseInt(p.getProperty("backspaceCode", "127"));
            // Match SessionStorage's clamp: only DEL (0x7F) or BS (0x08) are valid; anything
            // else would be narrowed to an arbitrary byte and sent to the SSH server.
            if (c.backspaceCode != 0x08 && c.backspaceCode != 0x7F) c.backspaceCode = 0x7F;
            current = c;
        } catch (Exception ignored) {}
    }

    private static void save() {
        Properties p = new Properties();
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
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            p.store(baos, null);
            SecureFiles.write(FILE, baos.toByteArray());
        } catch (IOException ignored) {}
    }
}
