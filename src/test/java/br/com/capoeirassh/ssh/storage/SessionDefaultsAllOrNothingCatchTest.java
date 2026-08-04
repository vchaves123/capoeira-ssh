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
 * Same fix as {@code AppearanceSettingsAllOrNothingCatchTest}: {@code SessionDefaults.load()}
 * used to wrap ALL field parsing in one outer {@code catch (Exception ignored)}, so a single
 * malformed int field discarded every other still-valid field in the file too. Fixed by giving
 * each int field its own {@code parseIntOr} fallback so failures stay local.
 *
 * <p>{@code load()} is a private static method run once from a static initializer, so this test
 * drives it directly via reflection after writing a crafted {@code session-defaults.properties}.
 */
class SessionDefaultsAllOrNothingCatchTest {

    private static Path file;

    @BeforeEach
    void verifyRedirected() {
        String home = System.getProperty("user.home");
        Assumptions.assumeTrue(home != null && home.contains("test-home"),
                "user.home was not redirected to a test directory (got: " + home + ") — refusing "
              + "to touch a real session-defaults.properties. Run via `mvn test`.");
        file = Path.of(home, ".capoeira", "session-defaults.properties");
    }

    @AfterEach
    void clean() throws Exception {
        Files.deleteIfExists(file);
    }

    private static void reload() throws Exception {
        Method m = SessionDefaults.class.getDeclaredMethod("load");
        m.setAccessible(true);
        m.invoke(null);
    }

    @Test
    void load_oneMalformedField_stillAppliesTheOtherValidFields() throws Exception {
        Files.createDirectories(file.getParent());
        Properties p = new Properties();
        p.setProperty("schemaVersion", "1");
        p.setProperty("appearFontSize", "not-a-number"); // malformed
        p.setProperty("appearFontName", "ValidFont");    // valid, non-default
        p.setProperty("appearFgR", "11");                // valid, non-default
        p.setProperty("appearFgG", "22");
        p.setProperty("appearFgB", "33");
        p.setProperty("terminalType", "xterm-256color");
        try (var out = Files.newOutputStream(file)) { p.store(out, null); }

        reload();

        var c = SessionDefaults.get();
        assertEquals(0, c.appearFontSize,
                "the malformed appearFontSize should fall back to its own default");
        assertEquals("ValidFont", c.appearFontName,
                "a valid field sitting next to a malformed one must still be applied, not "
              + "discarded by an all-or-nothing catch");
        assertEquals(11, c.appearFgR);
        assertEquals(22, c.appearFgG);
        assertEquals(33, c.appearFgB);
    }
}
