package br.com.capoeirassh.ssh.storage;

import br.com.capoeirassh.ssh.model.SessionInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code SessionStorage}'s {@code *.session} Properties files had no schema-version marker at
 * all, unlike {@code CredentialStore}'s vault or {@code BackupBundle} (both explicit binary
 * version bytes, cleanly rejecting anything newer than they understand). A hypothetical future
 * version changing the session format (a renamed key, a different value encoding) would have
 * been silently misread by an older build as all-defaults ({@code getProperty(key, default)} for
 * every key it no longer recognizes) instead of being detected and refused.
 *
 * <p>The fix adds a {@code schemaVersion} property, written by every {@code save()} and checked
 * by {@code load()}: absent (every file saved before this fix) is treated as compatible, present
 * and greater than the current {@code SCHEMA_VERSION} is refused (that one session is excluded
 * from {@code loadAll()}'s result rather than added with blank/wrong fields).
 */
class SessionStorageSchemaVersionTest {

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
    void clean() throws IOException {
        cleanSessionsDir();
    }

    private void cleanSessionsDir() throws IOException {
        if (!Files.exists(sessionsDir)) return;
        try (Stream<Path> files = Files.list(sessionsDir)) {
            for (Path p : files.toList()) {
                if (Files.isRegularFile(p)) Files.deleteIfExists(p);
            }
        }
    }

    /** Writes a raw *.session file directly (bypassing SessionStorage.save()), so the test can
     *  control exactly which properties (including schemaVersion, or its absence) end up in it. */
    private static void writeRawSession(String id, String host, Integer schemaVersion) throws IOException {
        Properties p = new Properties();
        if (schemaVersion != null) p.setProperty("schemaVersion", String.valueOf(schemaVersion));
        p.setProperty("name", "session-" + id);
        p.setProperty("host", host);
        p.setProperty("port", "22");
        p.setProperty("username", "alice");
        Path file = sessionsDir.resolve(id + ".session");
        try (var out = Files.newOutputStream(file)) {
            p.store(out, null);
        }
    }

    @Test
    void save_writesSchemaVersionProperty() throws Exception {
        SessionInfo s = new SessionInfo();
        s.id   = UUID.randomUUID().toString();
        s.name = "roundtrip";
        s.host = "roundtrip.example.com";
        SessionStorage.save(s);

        Path file = sessionsDir.resolve(s.id + ".session");
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) { p.load(in); }
        assertNotNull(p.getProperty("schemaVersion"), "save() must stamp a schemaVersion");
    }

    @Test
    void loadAll_acceptsFileWithNoSchemaVersion_forBackwardCompatibility() throws Exception {
        String id = UUID.randomUUID().toString();
        writeRawSession(id, "legacy.example.com", null); // no schemaVersion at all — pre-fix file

        List<SessionInfo> loaded = SessionStorage.loadAll();
        assertEquals(1, loaded.size());
        assertEquals("legacy.example.com", loaded.get(0).host);
    }

    @Test
    void loadAll_acceptsFileWithCurrentSchemaVersion() throws Exception {
        String id = UUID.randomUUID().toString();
        writeRawSession(id, "current.example.com", 1);

        List<SessionInfo> loaded = SessionStorage.loadAll();
        assertEquals(1, loaded.size());
        assertEquals("current.example.com", loaded.get(0).host);
    }

    @Test
    void loadAll_rejectsFileFromAFutureSchemaVersion_insteadOfSilentlyLoadingItWrong() throws Exception {
        String futureId = UUID.randomUUID().toString();
        String goodId    = UUID.randomUUID().toString();
        writeRawSession(futureId, "future.example.com", 99); // hypothetical future format
        writeRawSession(goodId,   "good.example.com",     1);

        List<SessionInfo> loaded = SessionStorage.loadAll();

        assertEquals(1, loaded.size(),
                "the future-schema session must be excluded, not silently loaded with wrong/blank fields");
        assertEquals("good.example.com", loaded.get(0).host);
        assertTrue(loaded.stream().noneMatch(s -> "future.example.com".equals(s.host)));
    }
}
