package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.model.SessionInfo;
import br.com.capoeirassh.ssh.storage.SessionStorage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code SessionsTab.commitRowReorder()} used to call the full {@code reload()} after a row drag
 * — re-reading and re-parsing every session file from disk and disposing/rebuilding every row
 * widget — an O(N) cost paid on every single drag, on top of the (separate, inherent) cost of
 * re-saving every session whose {@code sortOrder} shifted. Reorganizing a large list by dragging
 * rows one at a time therefore cost O(N) per drag just from the widget rebuild, O(N²)
 * cumulatively for N drags.
 *
 * <p>This test creates N real session files on disk (redirected to {@code target/test-home}, see
 * the {@code user.home} guard below), constructs a real {@link SessionsTab}, and compares the
 * wall-clock cost of applying a new row order via the OLD path ({@code reload()}, which re-reads
 * every file) against the NEW path ({@link SessionsTab#reorderRowWidgets}, which only repositions
 * the already-existing row widgets via {@code Control.moveAbove}/{@code moveBelow}).
 */
class SessionsTabReorderPerformanceTest {

    private static Path sessionsDir;

    @BeforeEach
    void verifyRedirectedAndClean() throws Exception {
        String home = System.getProperty("user.home");
        Assumptions.assumeTrue(home != null && home.contains("test-home"),
                "user.home was not redirected to a test directory (got: " + home + ") — refusing "
              + "to create hundreds of real session files. Run via `mvn test`.");
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
    @Timeout(60)
    @SuppressWarnings("unchecked")
    void reorderingRows_incrementalMoveIsMuchFasterThanFullReload() throws Exception {
        int n = 300;
        for (int i = 0; i < n; i++) {
            SessionInfo s = new SessionInfo();
            s.id = UUID.randomUUID().toString();
            s.name = "session-" + i;
            s.host = "host" + i + ".example.com";
            s.sortOrder = i;
            SessionStorage.save(s);
        }

        Display display = new Display();
        Shell shell = new Shell(display);
        try {
            CTabFolder folder = new CTabFolder(shell, SWT.NONE);
            SessionsTab tab = new SessionsTab(folder, shell,
                    (info, pw) -> {}, () -> {}, () -> {}, java.util.Collections::emptySet);

            Field orderField = SessionsTab.class.getDeclaredField("sessionOrder");
            orderField.setAccessible(true);
            List<SessionInfo> sessionOrder = (List<SessionInfo>) orderField.get(tab);
            assertEquals(n, sessionOrder.size(), "test setup: constructor's reload() should have loaded all " + n);

            // Move the first row to the very end — the largest possible reorder.
            List<SessionInfo> rotated = new ArrayList<>(sessionOrder);
            SessionInfo moved = rotated.remove(0);
            rotated.add(moved);

            long oldStart = System.nanoTime();
            tab.reload();
            long oldMs = (System.nanoTime() - oldStart) / 1_000_000;

            long newStart = System.nanoTime();
            tab.reorderRowWidgets(rotated);
            long newMs = (System.nanoTime() - newStart) / 1_000_000;

            System.out.println("Reordering rows for ~" + n + " sessions: full reload()=" + oldMs
                    + "ms, incremental reorderRowWidgets()=" + newMs + "ms");

            // Same rationale as SessionsTabSingleDeletePerformanceTest: reorderRowWidgets() still
            // pays SWT's own layout-recompute cost (inherent to this widget-per-row architecture),
            // but skips reload()'s O(N) disk re-read and full widget/Font teardown-and-rebuild —
            // assert the relative speedup rather than an unrealistic absolute bound.
            assertTrue(oldMs > newMs * 2,
                    "full reload() (" + oldMs + "ms) was expected to be at least 2x slower than "
                  + "the incremental reorder (" + newMs + "ms) at N=" + n + " sessions");
        } finally {
            if (!shell.isDisposed()) shell.dispose();
            if (!display.isDisposed()) display.dispose();
        }
    }
}
