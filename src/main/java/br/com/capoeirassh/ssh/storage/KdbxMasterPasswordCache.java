package br.com.capoeirassh.ssh.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Caches an external KeePass file's master password in memory for a short window, so connecting
 * to several sessions linked to the same .kdbx file doesn't re-prompt every time — but never
 * caches the decrypted database itself. Every password lookup still goes through the disposable
 * reader subprocess (see {@link KdbxSubprocessClient}), which opens the file, extracts one
 * password, and exits immediately; only the master password (needed to open it again next time)
 * is kept here, as a {@code char[]} this class explicitly zeroes on expiry, replacement, or an
 * explicit {@link #clearAll()}.
 *
 * Mirrors {@link CredentialStore}'s own auto-lock idea, and is cleared by it directly: locking
 * the vault (auto-lock from inactivity, or the user's manual "Lock vault" button) also drops
 * every cached KeePass master password, since they are equally sensitive secrets in memory.
 */
public final class KdbxMasterPasswordCache {

    private static final KdbxMasterPasswordCache INSTANCE = new KdbxMasterPasswordCache(5 * 60 * 1000L);

    public static KdbxMasterPasswordCache getInstance() { return INSTANCE; }

    private final long ttlMs;
    private final Map<String, CachedEntry> cache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "kdbx-password-cache-sweep");
        t.setDaemon(true);
        return t;
    });

    /** Package-private (not private) so a test can use a short TTL instead of waiting on the
     *  real 5-minute window. The public singleton above always uses the real one. */
    KdbxMasterPasswordCache(long ttlMs) {
        this.ttlMs = ttlMs;
        sweeper.scheduleAtFixedRate(this::sweepExpired, 30, 30, TimeUnit.SECONDS);
    }

    private record CachedEntry(char[] password, long expiresAtMs) {}

    /** Returns a copy of the cached password if present and not expired, else null. The caller
     *  owns the returned array and must zero it after use. */
    public char[] get(Path kdbxFile) {
        String key = normalize(kdbxFile);
        CachedEntry e = cache.get(key);
        if (e == null) return null;
        if (System.currentTimeMillis() >= e.expiresAtMs) {
            // Compare-and-remove: only drop the mapping if it's still exactly the stale entry we
            // just read. A plain remove(key) here could otherwise delete a brand-new, non-expired
            // entry that a concurrent put() inserted for the same file between our get() above and
            // this call — silently discarding a password that was just successfully (re)cached.
            cache.remove(key, e);
            Arrays.fill(e.password, '\0');
            return null;
        }
        return Arrays.copyOf(e.password, e.password.length);
    }

    /** Caches a copy of {@code password} for {@code kdbxFile}, resetting the expiry window. The
     *  caller retains ownership of the array passed in — still responsible for zeroing its own
     *  copy after this call, same as every other char[]-handling method in this codebase. */
    public void put(Path kdbxFile, char[] password) {
        char[] copy = Arrays.copyOf(password, password.length);
        CachedEntry prev = cache.put(normalize(kdbxFile),
                new CachedEntry(copy, System.currentTimeMillis() + ttlMs));
        if (prev != null) Arrays.fill(prev.password, '\0');
    }

    /** Drops the cached password for one file (e.g. it turned out to be stale/wrong), zeroing it. */
    public void remove(Path kdbxFile) {
        CachedEntry e = cache.remove(normalize(kdbxFile));
        if (e != null) Arrays.fill(e.password, '\0');
    }

    /** Clears every cached password immediately, zeroing each. Called from
     *  {@code CredentialStore.lock()} — see the class comment. */
    public void clearAll() {
        for (CachedEntry e : cache.values()) Arrays.fill(e.password, '\0');
        cache.clear();
    }

    private void sweepExpired() {
        long now = System.currentTimeMillis();
        // computeIfPresent runs its remapping function atomically per key (ConcurrentHashMap
        // holds that key's bin lock for the duration) — unlike entrySet().removeIf(), which reads
        // a value and later calls remove(key) as two separate steps, giving a concurrent put() a
        // window to insert a fresh, non-expired entry that then gets removed here instead of the
        // actually-stale one this loop meant to drop.
        for (String key : cache.keySet()) {
            cache.computeIfPresent(key, (k, entry) -> {
                if (now >= entry.expiresAtMs) {
                    Arrays.fill(entry.password, '\0');
                    return null; // removes the mapping
                }
                return entry;
            });
        }
    }

    /** Resolves symlinks/relative segments so the same file reached via two different-looking
     *  paths still hits the same cache entry; falls back to the absolute path if the file
     *  can't be resolved (e.g. momentarily unavailable on a network share). */
    private static String normalize(Path p) {
        try { return p.toRealPath().toString(); } catch (IOException ex) { return p.toAbsolutePath().toString(); }
    }
}
