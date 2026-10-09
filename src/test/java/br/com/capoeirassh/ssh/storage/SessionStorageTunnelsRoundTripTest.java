package br.com.capoeirassh.ssh.storage;

import br.com.capoeirassh.ssh.model.SessionInfo;
import br.com.capoeirassh.ssh.model.TunnelSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** save()/load() round trip of a session's tunnels, plus defensive loading of bad/old files. */
class SessionStorageTunnelsRoundTripTest {

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

    private static TunnelSpec tunnel(TunnelSpec.Type type, String bindHost, int bind, String dest, int destPort,
                                     String desc, boolean auto) {
        TunnelSpec t = new TunnelSpec();
        t.type = type; t.bindHost = bindHost; t.bindPort = bind;
        t.destHost = dest; t.destPort = destPort; t.description = desc; t.autoStart = auto;
        return t;
    }

    @Test
    void saveAndLoad_roundTripsEveryTunnelField() throws Exception {
        SessionInfo s = new SessionInfo();
        s.id = UUID.randomUUID().toString();
        s.name = "with-tunnels";
        s.tunnels.add(tunnel(TunnelSpec.Type.LOCAL,  "127.0.0.1", 5432, "db.internal", 5432, "Postgres, prod", true));
        s.tunnels.add(tunnel(TunnelSpec.Type.REMOTE, "",          9000, "127.0.0.1",   3000, "", false));

        SessionStorage.save(s);

        List<SessionInfo> loaded = SessionStorage.loadAll();
        assertEquals(1, loaded.size());
        assertEquals(s.tunnels, loaded.get(0).tunnels);
    }

    @Test
    void load_fileWrittenBeforeTunnelsExisted_yieldsEmptyList() throws Exception {
        SessionInfo s = new SessionInfo();
        s.id = UUID.randomUUID().toString();
        s.name = "old";
        SessionStorage.save(s);
        // Simulate an old file: strip every tunnel.* key.
        Path file = sessionsDir.resolve(s.fileName());
        Properties p = new Properties();
        try (var in = Files.newInputStream(file)) { p.load(in); }
        p.stringPropertyNames().stream().filter(k -> k.startsWith("tunnel.")).toList().forEach(p::remove);
        try (var out = Files.newOutputStream(file)) { p.store(out, null); }

        List<SessionInfo> loaded = SessionStorage.loadAll();
        assertEquals(1, loaded.size());
        assertTrue(loaded.get(0).tunnels.isEmpty());
    }

    @Test
    void readTunnels_dropsInvalidAndDuplicateEntries_andNeverThrows() {
        Properties p = new Properties();
        p.setProperty("tunnel.count", "5");
        // 0: valid
        p.setProperty("tunnel.0.type", "LOCAL"); p.setProperty("tunnel.0.bindHost", "127.0.0.1");
        p.setProperty("tunnel.0.bindPort", "8080"); p.setProperty("tunnel.0.destHost", "a"); p.setProperty("tunnel.0.destPort", "80");
        // 1: bad type
        p.setProperty("tunnel.1.type", "SOCKS"); p.setProperty("tunnel.1.bindPort", "1"); p.setProperty("tunnel.1.destHost", "a"); p.setProperty("tunnel.1.destPort", "1");
        // 2: bad port
        p.setProperty("tunnel.2.type", "LOCAL"); p.setProperty("tunnel.2.bindPort", "0"); p.setProperty("tunnel.2.destHost", "a"); p.setProperty("tunnel.2.destPort", "1");
        // 3: duplicate of 0 (same type + bind port)
        p.setProperty("tunnel.3.type", "LOCAL"); p.setProperty("tunnel.3.bindPort", "8080"); p.setProperty("tunnel.3.destHost", "b"); p.setProperty("tunnel.3.destPort", "81");
        // 4: non-numeric
        p.setProperty("tunnel.4.type", "LOCAL"); p.setProperty("tunnel.4.bindPort", "abc"); p.setProperty("tunnel.4.destHost", "a"); p.setProperty("tunnel.4.destPort", "1");

        List<TunnelSpec> out = SessionStorage.readTunnels(p);
        assertEquals(1, out.size());
        assertEquals(8080, out.get(0).bindPort);
        assertEquals("a", out.get(0).destHost);
    }

    @Test
    void readTunnels_capsTheCount_andToleratesGarbageCount() {
        Properties p = new Properties();
        p.setProperty("tunnel.count", "999999");
        for (int i = 0; i < TunnelSpec.MAX_PER_SESSION + 10; i++) {
            p.setProperty("tunnel." + i + ".type", "LOCAL");
            p.setProperty("tunnel." + i + ".bindPort", String.valueOf(1000 + i));
            p.setProperty("tunnel." + i + ".destHost", "h");
            p.setProperty("tunnel." + i + ".destPort", "1");
        }
        assertEquals(TunnelSpec.MAX_PER_SESSION, SessionStorage.readTunnels(p).size());

        Properties bad = new Properties();
        bad.setProperty("tunnel.count", "not-a-number");
        assertTrue(SessionStorage.readTunnels(bad).isEmpty());
        bad.setProperty("tunnel.count", "-4");
        assertTrue(SessionStorage.readTunnels(bad).isEmpty());
    }
}
