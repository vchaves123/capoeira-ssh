package br.com.capoeirassh.ssh.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Small, general cross-restart UI preferences (not scoped to any one dialog) —
 * distinct from {@link AppearanceSettings}, which is specifically terminal font/colors.
 */
public final class UiState {

    private static final Path FILE = Path.of(
            System.getProperty("user.home"), ".capoeira", "ui-state.properties");

    /** ALL SESSIONS list rendering mode — false = flat list, true = cards grouped by group. */
    private static boolean sessionsCardView = false;

    /** User dismissed an update-available prompt with "don't ask again" — suppresses future checks. */
    private static boolean updateAlertsDisabled = false;

    static { load(); }

    private UiState() {}

    public static boolean isSessionsCardView() { return sessionsCardView; }

    public static void setSessionsCardView(boolean cardView) {
        sessionsCardView = cardView;
        save();
    }

    public static boolean isUpdateAlertsDisabled() { return updateAlertsDisabled; }

    public static void setUpdateAlertsDisabled(boolean disabled) {
        updateAlertsDisabled = disabled;
        save();
    }

    private static void load() {
        if (!Files.exists(FILE)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(FILE)) {
            p.load(in);
            sessionsCardView = Boolean.parseBoolean(p.getProperty("sessionsCardView", "false"));
            updateAlertsDisabled = Boolean.parseBoolean(p.getProperty("updateAlertsDisabled", "false"));
        } catch (Exception ignored) {}
    }

    private static void save() {
        Properties p = new Properties();
        p.setProperty("sessionsCardView", String.valueOf(sessionsCardView));
        p.setProperty("updateAlertsDisabled", String.valueOf(updateAlertsDisabled));
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            p.store(baos, null);
            SecureFiles.write(FILE, baos.toByteArray());
        } catch (IOException ignored) {}
    }
}
