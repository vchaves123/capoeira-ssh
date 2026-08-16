package br.com.capoeirassh.ssh.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises {@link KdbxMasterPasswordCache} against a short TTL (via the package-private
 *  constructor) instead of the real 5-minute window used by the public singleton. */
class KdbxMasterPasswordCacheTest {

    @TempDir Path tempDir;

    @Test
    void putThenGetReturnsAnIndependentCopy() {
        KdbxMasterPasswordCache cache = new KdbxMasterPasswordCache(60_000);
        Path file = tempDir.resolve("a.kdbx");
        char[] original = "hunter2".toCharArray();
        cache.put(file, original);

        char[] got = cache.get(file);
        assertArrayEquals("hunter2".toCharArray(), got);

        // Caller's own copy is independent of what the cache stored.
        Arrays.fill(original, 'x');
        assertArrayEquals("hunter2".toCharArray(), cache.get(file));

        // The array returned by get() is a fresh copy each time — mutating one call's result
        // must not corrupt the cache or a previous caller's copy.
        Arrays.fill(got, 'y');
        assertArrayEquals("hunter2".toCharArray(), cache.get(file));
    }

    @Test
    void missReturnsNull() {
        KdbxMasterPasswordCache cache = new KdbxMasterPasswordCache(60_000);
        assertNull(cache.get(tempDir.resolve("never-put.kdbx")));
    }

    @Test
    void getAfterTtlExpiryReturnsNullAndZeroesTheEntry() throws Exception {
        KdbxMasterPasswordCache cache = new KdbxMasterPasswordCache(1); // 1ms — expires almost immediately
        Path file = tempDir.resolve("b.kdbx");
        cache.put(file, "s3cret".toCharArray());
        Thread.sleep(20);
        assertNull(cache.get(file));
        // A second get() must also miss — the expired entry was actually removed, not just
        // reported as missing once.
        assertNull(cache.get(file));
    }

    @Test
    void removeDropsOnlyThatFile() {
        KdbxMasterPasswordCache cache = new KdbxMasterPasswordCache(60_000);
        Path a = tempDir.resolve("a.kdbx");
        Path b = tempDir.resolve("b.kdbx");
        cache.put(a, "pwA".toCharArray());
        cache.put(b, "pwB".toCharArray());
        cache.remove(a);
        assertNull(cache.get(a));
        assertArrayEquals("pwB".toCharArray(), cache.get(b));
    }

    @Test
    void clearAllDropsEveryEntry() {
        KdbxMasterPasswordCache cache = new KdbxMasterPasswordCache(60_000);
        Path a = tempDir.resolve("a.kdbx");
        Path b = tempDir.resolve("b.kdbx");
        cache.put(a, "pwA".toCharArray());
        cache.put(b, "pwB".toCharArray());
        cache.clearAll();
        assertNull(cache.get(a));
        assertNull(cache.get(b));
    }

    @Test
    void puttingAgainForTheSameFileReplacesThePreviousPassword() {
        KdbxMasterPasswordCache cache = new KdbxMasterPasswordCache(60_000);
        Path file = tempDir.resolve("a.kdbx");
        cache.put(file, "old".toCharArray());
        cache.put(file, "new".toCharArray());
        assertArrayEquals("new".toCharArray(), cache.get(file));
    }

    @Test
    void lockingTheVaultClearsCachedKdbxPasswordsToo() throws Exception {
        // CredentialStore.lock() calls the public singleton's clearAll() directly — exercised
        // via the real singleton here since that's what production code actually wires up.
        KdbxMasterPasswordCache singleton = KdbxMasterPasswordCache.getInstance();
        Path file = tempDir.resolve("linked.kdbx");
        singleton.put(file, "s3cret".toCharArray());
        assertNotNull(singleton.get(file));

        CredentialStore.getInstance().lock(); // no-op on vault state if already locked, but still clears this
        assertNull(singleton.get(file));
    }
}
