package br.com.capoeirassh.ssh.storage;

import br.com.capoeirassh.ssh.model.SessionInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * save()/load() round trip of a session's {@code legacyAlgorithms} opt-in, which re-enables the
 * SHA-1-era algorithms needed to reach old network gear.
 *
 * <p>The backward-compatibility case matters most here: this flag weakens the host key check
 * that detects a man-in-the-middle, so a session file written before the option existed must
 * come back with it OFF, never inherit it by accident.
 */
class SessionStorageLegacyAlgorithmsRoundTripTest {

    private static Path sessionsDir;

    @BeforeEach
    void verifyRedirectedAndClean() throws Exception {
        String home = System.getProperty("user.home");
        Assumptions.assumeTrue(home != null && home.contains("test-home"),
                "user.home was not redirected to a test directory (got: " + home + ") — refusing "
              + "to touch real session files. Run via `mvn test`.");
        sessionsDir = Path.of(home, ".capoeira", "sessions");
        Files.createDirectories(sessionsDir);
        cleanSessionsDir();
    }

    @AfterEach
    void clean() throws IOException { cleanSessionsDir(); }

    private void cleanSessionsDir() throws IOException {
        if (!Files.exists(sessionsDir)) return;
        try (Stream<Path> files = Files.list(sessionsDir)) {
            for (Path p : files.toList()) if (Files.isRegularFile(p)) Files.deleteIfExists(p);
        }
    }

    private static SessionInfo session(String name, boolean legacy) {
        SessionInfo s = new SessionInfo();
        s.id = UUID.randomUUID().toString();
        s.name = name;
        s.host = "switch.internal";
        s.legacyAlgorithms = legacy;
        return s;
    }

    @Test
    @DisplayName("an enabled flag survives save and load")
    void enabledFlagRoundTrips() throws Exception {
        SessionStorage.save(session("old-switch", true));

        List<SessionInfo> loaded = SessionStorage.loadAll();
        assertEquals(1, loaded.size());
        assertTrue(loaded.get(0).legacyAlgorithms,
                "the user opted in per device — that choice has to outlive a restart, or they "
              + "would have to answer the same question on every launch");
    }

    @Test
    @DisplayName("a disabled flag survives save and load")
    void disabledFlagRoundTrips() throws Exception {
        SessionStorage.save(session("modern-server", false));

        List<SessionInfo> loaded = SessionStorage.loadAll();
        assertEquals(1, loaded.size());
        assertFalse(loaded.get(0).legacyAlgorithms);
    }

    @Test
    @DisplayName("a session file written before this option existed loads with it OFF")
    void fileWrittenBeforeTheOptionExisted_loadsDisabled() throws Exception {
        SessionInfo s = session("pre-existing", true);
        SessionStorage.save(s);

        // Simulate a file written by an older build: drop the property entirely.
        Path file = sessionsDir.resolve(s.fileName());
        Properties p = new Properties();
        try (var in = Files.newInputStream(file)) { p.load(in); }
        assertNotNull(p.remove("legacyAlgorithms"),
                "test premise: save() is expected to have written the property");
        try (var out = Files.newOutputStream(file)) { p.store(out, null); }

        List<SessionInfo> loaded = SessionStorage.loadAll();
        assertEquals(1, loaded.size());
        assertFalse(loaded.get(0).legacyAlgorithms,
                "every session that predates this option must stay on the modern-only algorithm "
              + "set — silently granting SHA-1 fallbacks to sessions nobody opted in for would "
              + "weaken host key verification across an entire existing install");
    }

    @Test
    @DisplayName("the flag is independent per session")
    void flagIsPerSession() throws Exception {
        SessionStorage.save(session("legacy-gear", true));
        SessionStorage.save(session("modern-gear", false));

        List<SessionInfo> loaded = SessionStorage.loadAll();
        assertEquals(2, loaded.size());
        assertTrue(loaded.stream().filter(s -> s.name.equals("legacy-gear"))
                         .findFirst().orElseThrow().legacyAlgorithms);
        assertFalse(loaded.stream().filter(s -> s.name.equals("modern-gear"))
                          .findFirst().orElseThrow().legacyAlgorithms,
                "enabling it for one device must not leak into the others");
    }
}
