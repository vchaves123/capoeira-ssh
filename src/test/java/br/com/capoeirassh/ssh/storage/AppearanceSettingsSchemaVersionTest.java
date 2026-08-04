package br.com.capoeirassh.ssh.storage;

import org.eclipse.swt.graphics.RGB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code AppearanceSettings}'s {@code appearance.properties} file had no schema-version marker
 * at all, unlike {@code CredentialStore}'s vault or {@code BackupBundle} (both explicit binary
 * version bytes, cleanly rejecting anything newer than they understand) — see the identical fix
 * just applied to {@code SessionStorage} for the full rationale. A hypothetical future format
 * change would have been silently misread as all-defaults instead of being detected and refused.
 *
 * <p>{@code load()} is a private static method run once from a static initializer, so this test
 * drives it directly via reflection after writing a crafted {@code appearance.properties} —
 * there is no public "reload" entry point.
 */
class AppearanceSettingsSchemaVersionTest {

    private static Path file;

    @BeforeEach
    void verifyRedirected() {
        String home = System.getProperty("user.home");
        Assumptions.assumeTrue(home != null && home.contains("test-home"),
                "user.home was not redirected to a test directory (got: " + home + ") — refusing "
              + "to touch a real appearance.properties. Run via `mvn test`.");
        file = Path.of(home, ".capoeira", "appearance.properties");
    }

    @AfterEach
    void clean() throws Exception {
        Files.deleteIfExists(file);
    }

    private static void reload() throws Exception {
        Method m = AppearanceSettings.class.getDeclaredMethod("load");
        m.setAccessible(true);
        m.invoke(null);
    }

    private static void writeRaw(Integer schemaVersion, int fontSize) throws Exception {
        Files.createDirectories(file.getParent());
        Properties p = new Properties();
        if (schemaVersion != null) p.setProperty("schemaVersion", String.valueOf(schemaVersion));
        p.setProperty("fontSize", String.valueOf(fontSize));
        p.setProperty("fontName", "TestFont");
        p.setProperty("fgColor", "1,2,3");
        p.setProperty("bgColor", "4,5,6");
        try (var out = Files.newOutputStream(file)) {
            p.store(out, null);
        }
    }

    @Test
    void set_writesSchemaVersionProperty() throws Exception {
        AppearanceSettings.set("Consolas", 14, new RGB(1, 2, 3), new RGB(4, 5, 6));
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) { p.load(in); }
        assertNotNull(p.getProperty("schemaVersion"), "set()/save() must stamp a schemaVersion");
    }

    @Test
    void load_acceptsFileWithNoSchemaVersion_forBackwardCompatibility() throws Exception {
        writeRaw(null, 33); // pre-fix file, no schemaVersion at all
        reload();
        assertEquals(33, AppearanceSettings.getFontSize());
    }

    @Test
    void load_acceptsFileWithCurrentSchemaVersion() throws Exception {
        writeRaw(1, 21);
        reload();
        assertEquals(21, AppearanceSettings.getFontSize());
    }

    @Test
    void load_rejectsFileFromAFutureSchemaVersion_insteadOfSilentlyApplyingIt() throws Exception {
        // Establish a known baseline first via the real, current-schema save path.
        AppearanceSettings.set("Baseline", 20, new RGB(9, 9, 9), new RGB(8, 8, 8));
        assertEquals(20, AppearanceSettings.getFontSize());

        // Now overwrite the file with a hypothetical future schema carrying a different value.
        writeRaw(99, 42);
        reload();

        assertEquals(20, AppearanceSettings.getFontSize(),
                "a future-schema file must be refused, leaving the prior in-memory settings "
              + "untouched rather than silently applying its (possibly wrong) values");
    }
}
