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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code SessionsTab.deleteSession()} used to call the full {@code reload()} after deleting a
 * single session — re-reading and re-parsing every remaining {@code *.session} file from disk
 * and disposing/rebuilding every row widget — an O(N) cost paid on every single delete. Deleting
 * sessions one at a time (the natural way to declutter a large list) therefore cost O(N²)
 * cumulatively for N deletions.
 *
 * <p>This test creates N real session files on disk (redirected to {@code target/test-home}, see
 * the {@code user.home} guard below), constructs a real {@link SessionsTab}, and compares the
 * wall-clock cost of deleting one session via the OLD path ({@code reload()}, which re-reads all
 * remaining files) against the NEW path ({@link SessionsTab#removeSessionFromUi}, which touches
 * disk only for the single deleted file and never re-reads the rest).
 */
@Tag("ui") // needs a real SWT Display — no display on the Linux/macOS CI runners
class SessionsTabSingleDeletePerformanceTest {

    private static Path sessionsDir;

    @BeforeEach
    void verifyRedirectedAndClean() throws Exception {
        String home = System.getProperty("user.home");
        Assumptions.assumeTrue(home != null && home.contains("test-home"),
                "user.home was not redirected to a test directory (got: " + home + ") — refusing "
              + "to create/delete thousands of real session files. Run via `mvn test`.");
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
    void deletingOneSession_incrementalRemovalIsMuchFasterThanFullReload() throws Exception {
        int n = 300;
        List<SessionInfo> sessions = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            SessionInfo s = new SessionInfo();
            s.id   = UUID.randomUUID().toString();
            s.name = "session-" + i;
            s.host = "host" + i + ".example.com";
            SessionStorage.save(s);
            sessions.add(s);
        }

        Display display = new Display();
        Shell shell = new Shell(display);
        try {
            CTabFolder folder = new CTabFolder(shell, SWT.NONE);
            SessionsTab tab = new SessionsTab(folder, shell,
                    (info, pw) -> {}, () -> {}, () -> {}, java.util.Collections::emptySet);

            // OLD behavior: delete one file, then pay the full reload() cost.
            SessionInfo toDeleteOldWay = sessions.get(0);
            SessionStorage.delete(toDeleteOldWay);
            long oldStart = System.nanoTime();
            tab.reload();
            long oldMs = (System.nanoTime() - oldStart) / 1_000_000;

            // NEW behavior: delete another file, then remove just that one row/entry.
            SessionInfo toDeleteNewWay = sessions.get(1);
            SessionStorage.delete(toDeleteNewWay);
            long newStart = System.nanoTime();
            tab.removeSessionFromUi(toDeleteNewWay);
            long newMs = (System.nanoTime() - newStart) / 1_000_000;

            System.out.println("Deleting 1 of ~" + n + " sessions: full reload()=" + oldMs
                    + "ms, incremental removeSessionFromUi()=" + newMs + "ms");

            // removeSessionFromUi() still pays SWT's own layout-recompute cost for the remaining
            // rows (inherent to this widget-per-row architecture, not something this fix changes)
            // — but it skips reload()'s O(N) disk re-read (re-parsing every remaining *.session
            // Properties file) and full widget/Font teardown-and-rebuild for every row, so it
            // should still be substantially faster overall. Assert the relative speedup rather
            // than an absolute bound, since the layout cost alone scales with N regardless.
            assertTrue(oldMs > newMs * 2,
                    "full reload() (" + oldMs + "ms) was expected to be at least 2x slower than "
                  + "the incremental removal (" + newMs + "ms) at N=" + n + " sessions");
        } finally {
            if (!shell.isDisposed()) shell.dispose();
            if (!display.isDisposed()) display.dispose();
        }
    }
}
