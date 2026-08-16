package br.com.capoeirassh.ssh.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.linguafranca.pwdb.Database;
import org.linguafranca.pwdb.Entry;
import org.linguafranca.pwdb.Group;
import org.linguafranca.pwdb.kdbx.KdbxCreds;
import org.linguafranca.pwdb.kdbx.jackson.JacksonDatabase;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test of {@link KdbxSubprocessClient} against a real .kdbx file, built directly with
 * KeePassJava2 as the fixture — this is the one test that actually launches the disposable
 * {@code KdbxReaderMain} child process (via {@code java -cp <test classpath> ...}), exercising
 * the whole stdin/stdout wire protocol end to end rather than just the parent-side parsing logic.
 */
class KdbxSubprocessClientIntegrationTest {

    @TempDir Path tempDir;

    private Path   kdbxFile;
    private String entryUuid;

    private static final char[] MASTER_PASSWORD = "correct horse battery staple".toCharArray();
    private static final String ENTRY_PASSWORD  = "s3cr3t-server-password!";

    @SuppressWarnings({"unchecked", "rawtypes"})
    @BeforeEach
    void createFixtureDatabase() throws Exception {
        // Raw types here — KeePassJava2's Database<D,G,E,I> self-referencing generics make a
        // wildcarded one-liner fixture more trouble than it's worth; this is test-only setup
        // code, not the production reader (see KdbxReaderMain, which uses proper wildcards).
        Database db = new JacksonDatabase();
        Group root = db.getRootGroup();
        Entry entry = db.newEntry("Prod Server");
        entry.setUsername("root");
        entry.setPassword(ENTRY_PASSWORD);
        entry.setUrl("ssh://10.0.0.5:2222");
        root.addEntry(entry);
        entryUuid = entry.getUuid().toString();

        kdbxFile = tempDir.resolve("fixture.kdbx");
        try (OutputStream out = Files.newOutputStream(kdbxFile)) {
            db.save(new KdbxCreds(new String(MASTER_PASSWORD).getBytes(StandardCharsets.UTF_8)), out);
        }
    }

    @Test
    void listEntriesReturnsTheOneEntryWithDisplayFieldsOnly() throws Exception {
        List<KdbxSubprocessClient.KdbxEntryInfo> entries =
                KdbxSubprocessClient.listEntries(kdbxFile, MASTER_PASSWORD.clone());
        assertEquals(1, entries.size());
        KdbxSubprocessClient.KdbxEntryInfo info = entries.get(0);
        assertEquals("Prod Server", info.title());
        assertEquals("root", info.username());
        assertEquals(entryUuid, info.uuid());
        assertEquals("ssh://10.0.0.5:2222", info.url());
    }

    @Test
    void fetchPasswordReturnsTheStoredPassword() throws Exception {
        char[] pw = KdbxSubprocessClient.fetchPassword(kdbxFile, MASTER_PASSWORD.clone(), entryUuid);
        assertEquals(ENTRY_PASSWORD, new String(pw));
    }

    @Test
    void wrongMasterPasswordThrowsWrongPasswordReason() {
        KdbxSubprocessClient.KdbxException ex = assertThrows(KdbxSubprocessClient.KdbxException.class,
            () -> KdbxSubprocessClient.listEntries(kdbxFile, "totally wrong".toCharArray()));
        assertEquals(KdbxSubprocessClient.KdbxException.Reason.WRONG_PASSWORD, ex.reason);
    }

    @Test
    void unknownEntryUuidThrowsEntryNotFoundReason() {
        KdbxSubprocessClient.KdbxException ex = assertThrows(KdbxSubprocessClient.KdbxException.class,
            () -> KdbxSubprocessClient.fetchPassword(kdbxFile, MASTER_PASSWORD.clone(), UUID.randomUUID().toString()));
        assertEquals(KdbxSubprocessClient.KdbxException.Reason.ENTRY_NOT_FOUND, ex.reason);
    }

    @Test
    void missingFileThrowsFileNotFoundReason() {
        Path missing = tempDir.resolve("does-not-exist.kdbx");
        KdbxSubprocessClient.KdbxException ex = assertThrows(KdbxSubprocessClient.KdbxException.class,
            () -> KdbxSubprocessClient.listEntries(missing, MASTER_PASSWORD.clone()));
        assertEquals(KdbxSubprocessClient.KdbxException.Reason.FILE_NOT_FOUND, ex.reason);
    }

    @Test
    void callerSuppliedMasterPasswordArrayIsNotModified() throws Exception {
        char[] pw = MASTER_PASSWORD.clone();
        KdbxSubprocessClient.fetchPassword(kdbxFile, pw, entryUuid);
        assertArrayEquals(MASTER_PASSWORD, pw, "fetchPassword must not mutate the caller's array");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void listEntriesReportsProgressAsEachEntryStreamsIn() throws Exception {
        // A separate multi-entry fixture — the shared @BeforeEach one only has a single entry,
        // which can't tell an incrementing counter apart from a single "done" callback.
        Database db = new JacksonDatabase();
        Group root = db.getRootGroup();
        for (String title : List.of("Server A", "Server B", "Server C")) {
            Entry entry = db.newEntry(title);
            entry.setUsername("user-" + title);
            entry.setPassword("irrelevant");
            root.addEntry(entry);
        }
        Path multiFile = tempDir.resolve("multi.kdbx");
        try (OutputStream out = Files.newOutputStream(multiFile)) {
            db.save(new KdbxCreds(new String(MASTER_PASSWORD).getBytes(StandardCharsets.UTF_8)), out);
        }

        List<Integer> countsSeen = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<KdbxSubprocessClient.KdbxEntryInfo> entries =
                KdbxSubprocessClient.listEntries(multiFile, MASTER_PASSWORD.clone(), countsSeen::add);

        assertEquals(3, entries.size());
        assertEquals(List.of(1, 2, 3), countsSeen, "the counter must increase by exactly one per entry, in order");
    }
}
