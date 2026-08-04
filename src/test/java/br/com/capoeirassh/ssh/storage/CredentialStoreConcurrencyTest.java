package br.com.capoeirassh.ssh.storage;

import br.com.capoeirassh.ssh.model.CredentialEntry;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency tests for {@link CredentialStore} — a singleton whose {@code lockTimer} background
 * thread (auto-lock after 5 minutes of inactivity, see {@code INACTIVITY_MS}) is running for the
 * whole JVM lifetime, contending for the same monitor lock as every foreground call. All public
 * mutators are {@code synchronized}, so these tests are really asking: does that synchronization
 * actually hold up under real thread contention (no deadlock, no corruption, no unexpected
 * exception), not "can we provoke a race" — the language-level lock rules that out by construction.
 *
 * Same safety mechanism as {@link CredentialStoreTest}: surefire (pom.xml) redirects user.home to
 * target/test-home for the forked test JVM, so CredentialStore.VAULT never resolves to the real
 * ~/.capoeira/credentials.vault. verifyVaultIsRedirected() aborts the whole class otherwise.
 */
class CredentialStoreConcurrencyTest {

    private static Path vaultPath;

    @BeforeAll
    static void verifyVaultIsRedirected() throws Exception {
        Field f = CredentialStore.class.getDeclaredField("VAULT");
        f.setAccessible(true);
        vaultPath = (Path) f.get(null);
        String home = System.getProperty("user.home");
        Assumptions.assumeTrue(home != null && home.contains("test-home"),
                "user.home was not redirected to a test directory (got: " + home + ") — "
              + "refusing to run tests that write to CredentialStore's vault path. "
              + "Run via `mvn test` (surefire applies the redirect).");
    }

    @BeforeEach
    void cleanVault() throws Exception {
        Files.createDirectories(vaultPath.getParent());
        Files.deleteIfExists(vaultPath);
        if (CredentialStore.getInstance().isUnlocked()) CredentialStore.getInstance().lock();
    }

    @AfterEach
    void lockAndClean() throws Exception {
        if (CredentialStore.getInstance().isUnlocked()) CredentialStore.getInstance().lock();
        Files.deleteIfExists(vaultPath);
    }

    private static CredentialEntry entry(String id, String label, char[] password) {
        CredentialEntry e = new CredentialEntry();
        e.id       = id;
        e.label    = label;
        e.username = "user";
        e.keyPath  = "";
        e.password = password;
        return e;
    }

    // -----------------------------------------------------------------------
    // N threads hammering addOrUpdate/delete/getAll while the real auto-lock
    // timer thread is alive in the background (it always is — singleton ctor).
    // -----------------------------------------------------------------------

    @RepeatedTest(25)
    @Timeout(30)
    @DisplayName("N threads calling addOrUpdate/delete/getAll concurrently: no exception, no deadlock, no corruption")
    void concurrentCrud_manyThreads_noExceptionNoDeadlockNoCorruption() throws Exception {
        CredentialStore store = CredentialStore.getInstance();
        store.create("master-password".toCharArray());

        final int threadCount = 8;
        final int opsPerThread = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Exception>> futures = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            final int threadIdx = t;
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        String id = "t" + threadIdx + "-" + i;
                        store.addOrUpdate(entry(id, "label-" + id, ("pw" + i).toCharArray()));
                        store.getAll(); // concurrent read interleaved with writes
                        if (i % 3 == 0) store.delete(id);
                    }
                    return null;
                } catch (Exception ex) {
                    return ex;
                }
            }));
        }

        ready.await();
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS),
                "threads did not finish in time — possible deadlock in CredentialStore");

        List<Exception> errors = new ArrayList<>();
        for (Future<Exception> f : futures) {
            Exception ex = f.get();
            if (ex != null) errors.add(ex);
        }
        assertTrue(errors.isEmpty(), "unexpected exceptions from concurrent CRUD: " + errors);

        // The store must still be in a consistent, readable state afterwards.
        List<CredentialEntry> all = assertDoesNotThrow(store::getAll);
        // Each thread added 40 and deleted every 3rd (indices 0,3,6,...,39 -> 14 deletes),
        // so 40 - 14 = 26 should remain per thread, 8 threads => 208 total.
        assertEquals(threadCount * (opsPerThread - 14), all.size(),
                "entry count must reflect exactly the adds/deletes actually issued — a lost or "
              + "duplicated write here would indicate the synchronized methods aren't giving "
              + "each other a consistent view of `entries`");
    }

    // -----------------------------------------------------------------------
    // One thread unlock()ing at the same instant another thread lock()s.
    // -----------------------------------------------------------------------

    @RepeatedTest(25)
    @Timeout(15)
    @DisplayName("unlock() racing lock() on separate threads: no deadlock, no exception, ends in one coherent state")
    void unlockRacingLock_noDeadlockNoExceptionCoherentFinalState() throws Exception {
        char[] password = "correct horse battery staple".toCharArray();
        CredentialStore store = CredentialStore.getInstance();
        store.create(password.clone());
        store.lock(); // vault file now exists on disk, store starts this test locked

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Future<Exception> unlockResult = pool.submit(() -> {
            ready.countDown();
            try { go.await(); store.unlock(password.clone()); return null; }
            catch (Exception ex) { return ex; }
        });
        Future<Exception> lockResult = pool.submit(() -> {
            ready.countDown();
            try { go.await(); store.lock(); return null; }
            catch (Exception ex) { return ex; }
        });

        ready.await();
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS),
                "possible deadlock between concurrent unlock() and lock()");

        assertNull(lockResult.get(), "lock() must never throw");
        assertNull(unlockResult.get(),
                "unlock() with the correct password against a valid file must not throw, "
              + "even when racing a concurrent lock()");

        // Whichever call actually ran last (nondeterministic — both orders are legitimate),
        // the store must be in one coherent state, never a half-updated mix of the two.
        boolean unlocked = store.isUnlocked();
        List<CredentialEntry> all = assertDoesNotThrow(store::getAll);
        if (unlocked) {
            assertEquals(0, all.size(), "freshly unlocked vault created empty — should read back empty");
        } else {
            assertTrue(all.isEmpty(), "a locked store must report no entries");
        }
    }
}
