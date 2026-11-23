package br.com.capoeirassh.ssh.storage;

import br.com.capoeirassh.ssh.model.ConfigurationSettings;
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
 * The per-session OSC 52 opt-in ({@code allowClipboardWrite}). It lets a program on the server
 * replace the local clipboard, so it must default to OFF — for new sessions, for the global
 * defaults, and for session files written before the option existed.
 */
class SessionStorageClipboardWriteRoundTripTest {

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

    private static SessionInfo session(String name, boolean allow) {
        SessionInfo s = new SessionInfo();
        s.id = UUID.randomUUID().toString();
        s.name = name;
        s.host = "server.internal";
        s.allowClipboardWrite = allow;
        return s;
    }

    @Test
    @DisplayName("off by default for a new session and in the settings model")
    void offByDefault() {
        assertFalse(new SessionInfo().allowClipboardWrite);
        assertFalse(new ConfigurationSettings().allowClipboardWrite);
    }

    @Test
    @DisplayName("an enabled flag survives save and load, and copy/settings round trips")
    void enabledFlagRoundTrips() throws Exception {
        SessionInfo s = session("tmux-box", true);
        assertTrue(s.copy().allowClipboardWrite, "SessionInfo.copy() must carry it");
        assertTrue(ConfigurationSettings.fromSession(s).copy().allowClipboardWrite,
                "the settings dialog model must carry it");

        SessionStorage.save(s);
        List<SessionInfo> loaded = SessionStorage.loadAll();
        assertEquals(1, loaded.size());
        assertTrue(loaded.get(0).allowClipboardWrite);
    }

    @Test
    @DisplayName("a session file written before this option existed loads with it OFF")
    void fileWrittenBeforeTheOptionExisted_loadsDisabled() throws Exception {
        SessionInfo s = session("pre-existing", true);
        SessionStorage.save(s);

        Path file = sessionsDir.resolve(s.fileName());
        Properties p = new Properties();
        try (var in = Files.newInputStream(file)) { p.load(in); }
        assertNotNull(p.remove("allowClipboardWrite"),
                "test premise: save() is expected to have written the property");
        try (var out = Files.newOutputStream(file)) { p.store(out, null); }

        List<SessionInfo> loaded = SessionStorage.loadAll();
        assertEquals(1, loaded.size());
        assertFalse(loaded.get(0).allowClipboardWrite,
                "existing sessions must not gain clipboard write access nobody opted in for");
    }
}
