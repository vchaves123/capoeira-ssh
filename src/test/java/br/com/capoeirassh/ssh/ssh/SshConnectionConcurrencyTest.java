package br.com.capoeirassh.ssh.ssh;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency test for {@link SshConnection#send(byte[])}: simulates keystrokes (short bursts,
 * as sent from the SWT UI thread by {@code TerminalTab.handleKey}) racing against a multi-line
 * paste (longer per-line chunks, as sent from the background {@code "paste-lines"} thread by
 * {@code TerminalTab.sendPastedLines}) — see the javadoc on {@code send()} itself, which already
 * documents this exact race as the reason the method is synchronized.
 *
 * No production code is modified. {@code SshConnection} has no constructor/setter for injecting
 * a test {@link OutputStream}, so the private {@code output} field is set via reflection —
 * the same pattern used in {@code CredentialStoreTest}/{@code CredentialStoreConcurrencyTest}.
 */
class SshConnectionConcurrencyTest {

    /**
     * Records every {@code write(byte[], off, len)} call into a single shared, deliberately
     * UN-synchronized buffer, splitting each call's payload into two halves with a short sleep in
     * between. If two callers ever executed {@code write()} concurrently (i.e. if
     * {@link SshConnection#send} did not serialize them), the second caller's bytes could land in
     * the buffer during the first caller's sleep — splitting the first call's marker-byte run in
     * two. Every byte within one call is set to the same marker value (0..255, unique per call),
     * so that corruption shows up as a single call's marker appearing as more than one contiguous
     * run in the final buffer.
     */
    private static class SlowRecordingOutputStream extends OutputStream {
        final List<Byte> buffer = new ArrayList<>(); // intentionally not synchronized

        @Override public void write(int b) { throw new UnsupportedOperationException("unused"); }

        @Override
        public void write(byte[] b, int off, int len) {
            int half = Math.max(1, len / 2);
            for (int i = 0; i < half; i++) buffer.add(b[off + i]);
            try { Thread.sleep(3); } catch (InterruptedException ignored) {}
            for (int i = half; i < len; i++) buffer.add(b[off + i]);
        }

        @Override public void flush() { /* no-op */ }
    }

    private static SshConnection newConnectionWithMockOutput(OutputStream out) throws Exception {
        SshConnection conn = new SshConnection();
        Field f = SshConnection.class.getDeclaredField("output");
        f.setAccessible(true);
        f.set(conn, out);
        return conn;
    }

    @RepeatedTest(20)
    @Timeout(30)
    @DisplayName("Simulated keystrokes and a multi-line paste sent concurrently via send() never interleave bytes mid-write")
    void concurrentKeystrokesAndPaste_neverInterleaveBytesMidWrite() throws Exception {
        SlowRecordingOutputStream mockOut = new SlowRecordingOutputStream();
        SshConnection conn = newConnectionWithMockOutput(mockOut);

        // "Keystrokes": short 1-3 byte bursts, as TerminalTab.handleKey sends from the UI thread.
        final int typistThreads = 3;
        final int keystrokesPerTypist = 6;
        // "Multi-line paste": longer per-line chunks, as TerminalTab.sendPastedLines sends one
        // call per pasted line from its background "paste-lines" thread.
        final int pasteThreads = 2;
        final int linesPerPaste = 4;
        final int lineLength = 48;

        int totalCalls = typistThreads * keystrokesPerTypist + pasteThreads * linesPerPaste;
        ExecutorService pool = Executors.newFixedThreadPool(typistThreads + pasteThreads);
        CountDownLatch ready = new CountDownLatch(typistThreads + pasteThreads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Exception>> futures = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger callIdSeq = new java.util.concurrent.atomic.AtomicInteger(0);
        List<Integer> expectedLens = Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < typistThreads; t++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    for (int k = 0; k < keystrokesPerTypist; k++) {
                        int callId = callIdSeq.getAndIncrement();
                        int len = 1 + (callId % 3); // 1-3 bytes, like a real keystroke's escape seq
                        byte[] payload = new byte[len];
                        Arrays.fill(payload, (byte) callId);
                        expectedLens.add(len);
                        conn.send(payload);
                    }
                    return null;
                } catch (Exception ex) {
                    return ex;
                }
            }));
        }
        for (int p = 0; p < pasteThreads; p++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    for (int l = 0; l < linesPerPaste; l++) {
                        int callId = callIdSeq.getAndIncrement();
                        byte[] payload = new byte[lineLength];
                        Arrays.fill(payload, (byte) callId);
                        expectedLens.add(lineLength);
                        conn.send(payload);
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
        assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS), "sends did not finish — possible deadlock in SshConnection.send()");

        for (Future<Exception> f : futures) {
            Exception ex = f.get();
            assertNull(ex, "send() must not throw: " + ex);
        }
        assertEquals(totalCalls, expectedLens.size(), "test setup sanity: expected one recorded length per call");

        // Reconstruct contiguous runs of identical marker bytes and confirm each call's payload
        // landed as ONE unbroken run in the shared buffer, never split by another call's bytes.
        List<int[]> runs = new ArrayList<>(); // [markerValue, runLength]
        int i = 0;
        List<Byte> buf = mockOut.buffer;
        while (i < buf.size()) {
            int value = buf.get(i) & 0xFF;
            int j = i;
            while (j < buf.size() && (buf.get(j) & 0xFF) == value) j++;
            runs.add(new int[]{value, j - i});
            i = j;
        }

        Map<Integer, Integer> runCountByMarker = new HashMap<>();
        for (int[] run : runs) runCountByMarker.merge(run[0], 1, Integer::sum);

        for (int callId = 0; callId < totalCalls; callId++) {
            assertEquals(1, runCountByMarker.getOrDefault(callId, 0),
                    "call #" + callId + " appeared as " + runCountByMarker.getOrDefault(callId, 0)
                  + " separate run(s) instead of one contiguous block — bytes from a concurrent "
                  + "send() (keystroke vs. paste line) were interleaved into the middle of this write");
        }

        int expectedTotalBytes = expectedLens.stream().mapToInt(Integer::intValue).sum();
        assertEquals(expectedTotalBytes, buf.size(), "total byte count must match every payload written exactly once, intact");
    }
}
