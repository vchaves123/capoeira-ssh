package br.com.capoeirassh.ssh.storage;

import br.com.capoeirassh.ssh.model.ConfigurationSettings;
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
 * Same fix as {@code SessionStorage}/{@code AppearanceSettings}: {@code SessionDefaults}'
 * {@code session-defaults.properties} had no schema-version marker at all, unlike
 * {@code CredentialStore}'s vault or {@code BackupBundle}. A hypothetical future format change
 * would have been silently misread as all-defaults instead of being detected and refused.
 *
 * <p>{@code load()} is a private static method run once from a static initializer, so this test
 * drives it directly via reflection after writing a crafted {@code session-defaults.properties}.
 */
class SessionDefaultsSchemaVersionTest {

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

    private static void writeRaw(Integer schemaVersion, int appearFontSize) throws Exception {
        Files.createDirectories(file.getParent());
        Properties p = new Properties();
        if (schemaVersion != null) p.setProperty("schemaVersion", String.valueOf(schemaVersion));
        p.setProperty("appearFontSize", String.valueOf(appearFontSize));
        p.setProperty("appearFontName", "TestFont");
        p.setProperty("terminalType", "xterm-256color");
        try (var out = Files.newOutputStream(file)) {
            p.store(out, null);
        }
    }

    private static ConfigurationSettings settingsWith(int appearFontSize) {
        ConfigurationSettings c = new ConfigurationSettings();
        c.appearFontSize = appearFontSize;
        c.appearFontName = "Baseline";
        return c;
    }

    @Test
    void set_writesSchemaVersionProperty() throws Exception {
        SessionDefaults.set(settingsWith(14));
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) { p.load(in); }
        assertNotNull(p.getProperty("schemaVersion"), "set()/save() must stamp a schemaVersion");
    }

    @Test
    void load_acceptsFileWithNoSchemaVersion_forBackwardCompatibility() throws Exception {
        writeRaw(null, 33); // pre-fix file, no schemaVersion at all
        reload();
        assertEquals(33, SessionDefaults.get().appearFontSize);
    }

    @Test
    void load_acceptsFileWithCurrentSchemaVersion() throws Exception {
        writeRaw(1, 21);
        reload();
        assertEquals(21, SessionDefaults.get().appearFontSize);
    }

    @Test
    void load_rejectsFileFromAFutureSchemaVersion_insteadOfSilentlyApplyingIt() throws Exception {
        // Establish a known baseline first via the real, current-schema save path.
        SessionDefaults.set(settingsWith(20));
        assertEquals(20, SessionDefaults.get().appearFontSize);

        // Now overwrite the file with a hypothetical future schema carrying a different value.
        writeRaw(99, 42);
        reload();

        assertEquals(20, SessionDefaults.get().appearFontSize,
                "a future-schema file must be refused, leaving the prior in-memory settings "
              + "untouched rather than silently applying its (possibly wrong) values");
    }
}
