package br.com.capoeirassh.ssh.storage;

import br.com.capoeirassh.ssh.model.CredentialEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code CredentialStore.findById}/{@code addOrUpdate}/{@code delete} used to look up an entry
 * by id via a linear {@code entries.stream().filter(e -> e.id.equals(id)).findFirst()} scan over
 * a {@code List<CredentialEntry>} — O(N) per call. This test exercises {@code findById}, the
 * simplest of the three to drive without touching disk (it doesn't check the lock state or call
 * persist()), against a vault with N entries populated directly via reflection — bypassing
 * create()/persist() so this measures only the id lookup, not AES-GCM encryption or file I/O.
 *
 * <p>Calling findById() once per id (N calls total) is O(N²) with the old linear-scan
 * implementation and O(N) with the fix (entries keyed by id in a {@code LinkedHashMap}).
 */
class CredentialStoreLookupPerformanceTest {

    private static void setEntries(CredentialStore store, Map<String, CredentialEntry> entries) throws Exception {
        Field f = CredentialStore.class.getDeclaredField("entries");
        f.setAccessible(true);
        f.set(store, entries);
    }

    /** CredentialStore is a JVM-wide singleton shared with every other test class in this
     *  forked JVM — reset its in-memory entries afterward so this test's 20,000 synthetic,
     *  never-persisted entries don't leak into any test that runs later in the same fork. */
    @AfterEach
    void resetEntries() throws Exception {
        setEntries(CredentialStore.getInstance(), new LinkedHashMap<>());
    }

    @Test
    @Timeout(60)
    void findByIdRepeatedly_scalesLinearlyNotQuadratically() throws Exception {
        CredentialStore store = CredentialStore.getInstance();

        int n = 20_000;
        Map<String, CredentialEntry> entries = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            CredentialEntry e = new CredentialEntry();
            e.id = "cred-" + i;
            e.label = "label-" + i;
            e.username = "user";
            e.password = "pw".toCharArray();
            entries.put(e.id, e);
            ids.add(e.id);
        }
        setEntries(store, entries);

        long start = System.nanoTime();
        for (String id : ids) {
            CredentialEntry found = store.findById(id);
            assertTrue(found != null && found.id.equals(id));
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("findById() x " + n + " (against " + n + " entries) took " + elapsedMs + "ms");
        assertTrue(elapsedMs < 3_000,
                "looking up all " + n + " entries by id took " + elapsedMs + "ms — expected well under "
              + "3s with an O(1) map lookup; the previous O(N) per-call linear scan is quadratic "
              + "overall at N=" + n + " and far exceeds this");
    }
}
