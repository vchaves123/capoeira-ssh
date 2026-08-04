package br.com.capoeirassh.ssh.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code AppearanceSettings.load()} used to wrap ALL field parsing in one outer
 * {@code catch (Exception ignored)}, so a single malformed field (e.g. a hand-edited
 * {@code fontSize}) discarded every other still-valid field in the file too, reverting the
 * whole class to its built-in defaults instead of just that one field. Fixed by giving each
 * int field its own {@code parseIntOr} fallback so failures stay local.
 *
 * <p>{@code load()} is a private static method run once from a static initializer, so this test
 * drives it directly via reflection after writing a crafted {@code appearance.properties}.
 */
class AppearanceSettingsAllOrNothingCatchTest {

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

    @Test
    void load_oneMalformedField_stillAppliesTheOtherValidFields() throws Exception {
        Files.createDirectories(file.getParent());
        Properties p = new Properties();
        p.setProperty("schemaVersion", "1");
        p.setProperty("fontSize", "not-a-number"); // malformed
        p.setProperty("fontName", "ValidFont");    // valid, non-default
        p.setProperty("fgColor", "1,2,3");         // valid, non-default
        p.setProperty("bgColor", "4,5,6");         // valid, non-default
        try (var out = Files.newOutputStream(file)) { p.store(out, null); }

        reload();

        assertEquals(12, AppearanceSettings.getFontSize(),
                "the malformed fontSize should fall back to its own default");
        assertEquals("ValidFont", AppearanceSettings.getFontName(),
                "a valid field sitting next to a malformed one must still be applied, not "
              + "discarded by an all-or-nothing catch");
        assertEquals(1, AppearanceSettings.getFgColor().red);
        assertEquals(2, AppearanceSettings.getFgColor().green);
        assertEquals(3, AppearanceSettings.getFgColor().blue);
    }
}
