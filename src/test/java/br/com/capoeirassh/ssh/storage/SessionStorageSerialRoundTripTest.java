package br.com.capoeirassh.ssh.storage;

import br.com.capoeirassh.ssh.model.SessionInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code SessionStorage} save()/load() round-trip for a {@link SessionInfo.ConnectionType#SERIAL}
 * session — added alongside RS232 terminal support. Mirrors {@code SessionStorageSchemaVersionTest}'s
 * setup (redirected {@code user.home}, cleaned sessions dir per test).
 */
class SessionStorageSerialRoundTripTest {

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

    @Test
    void saveAndLoad_roundTripsEverySerialField() throws Exception {
        SessionInfo s = new SessionInfo();
        s.id   = UUID.randomUUID().toString();
        s.name = "bench-scope";
        s.connectionType    = SessionInfo.ConnectionType.SERIAL;
        s.serialPortName    = "COM7";
        s.serialBaudRate    = 115200;
        s.serialDataBits    = 7;
        s.serialParity      = SessionInfo.SerialParity.EVEN;
        s.serialStopBits    = 2;
        s.serialFlowControl = SessionInfo.SerialFlowControl.RTS_CTS;
        s.serialLocalEcho   = true;

        SessionStorage.save(s);

        List<SessionInfo> loaded = SessionStorage.loadAll();
        assertEquals(1, loaded.size());
        SessionInfo l = loaded.get(0);

        assertEquals(SessionInfo.ConnectionType.SERIAL, l.connectionType);
        assertEquals("COM7",   l.serialPortName);
        assertEquals(115200,   l.serialBaudRate);
        assertEquals(7,        l.serialDataBits);
        assertEquals(SessionInfo.SerialParity.EVEN, l.serialParity);
        assertEquals(2,        l.serialStopBits);
        assertEquals(SessionInfo.SerialFlowControl.RTS_CTS, l.serialFlowControl);
        assertTrue(l.serialLocalEcho);
    }

    @Test
    void load_fileWithNoConnectionTypeProperty_defaultsToSsh() throws Exception {
        // A *.session file saved by a build before serial support existed — no connectionType
        // property at all. Must default to SSH, not silently become an unusable serial session.
        SessionInfo s = new SessionInfo();
        s.id   = UUID.randomUUID().toString();
        s.name = "legacy-ssh";
        s.host = "legacy.example.com";
        SessionStorage.save(s);

        List<SessionInfo> loaded = SessionStorage.loadAll();
        assertEquals(1, loaded.size());
        assertEquals(SessionInfo.ConnectionType.SSH, loaded.get(0).connectionType);
    }
}
