package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.model.SessionInfo;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code SessionsTab.deleteSelectedSessions()} used to look up each selected id with a linear
 * {@code sessionOrder.stream().filter(s -> s.id.equals(id)).findFirst()} scan — O(N) per id,
 * O(N·K) overall for K selected ids out of N total sessions. Selecting and deleting every
 * session (K=N) was therefore O(N²).
 *
 * <p>This test exercises {@link SessionsTab#deleteSessionsByIds}, the extracted, package-private
 * method the fix introduced, directly — bypassing the confirmation {@code MessageBox} (a native
 * OS dialog on Windows, not an SWT widget tree, so unlike the custom dialogs tested elsewhere
 * this session it can't be driven by finding/clicking a widget). It measures wall-clock time
 * deleting every one of N synthetic sessions (K=N, the worst case) and asserts it stays well
 * under a threshold that an O(N²) string-comparison scan blows past at this N, but an O(N) map
 * lookup clears easily.
 */
class SessionsTabDeletePerformanceTest {

    @Test
    @Timeout(60)
    @SuppressWarnings("unchecked")
    void deletingAllSelectedSessions_scalesLinearlyNotQuadratically() throws Exception {
        Display display = new Display();
        Shell shell = new Shell(display);
        try {
            CTabFolder folder = new CTabFolder(shell, SWT.NONE);
            SessionsTab tab = new SessionsTab(folder, shell,
                    (info, pw) -> {}, () -> {}, () -> {}, java.util.Collections::emptySet);

            Field orderField = SessionsTab.class.getDeclaredField("sessionOrder");
            orderField.setAccessible(true);
            List<SessionInfo> sessionOrder = (List<SessionInfo>) orderField.get(tab);

            int n = 40_000;
            List<String> ids = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                SessionInfo s = new SessionInfo();
                s.id = "synthetic-session-" + i;
                s.group = "";
                sessionOrder.add(s);
                ids.add(s.id);
            }
            Set<String> allIds = new LinkedHashSet<>(ids);

            long start = System.nanoTime();
            tab.deleteSessionsByIds(allIds);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            System.out.println("deleteSessionsByIds(" + n + " ids against " + n + " sessions) took " + elapsedMs + "ms");
            assertTrue(elapsedMs < 3_000,
                    "deleting all " + n + " selected sessions took " + elapsedMs + "ms — expected well under "
                  + "3s with an O(N) id->session lookup; the previous O(N*K) per-id linear scan is quadratic "
                  + "at N=K=" + n + " and far exceeds this");
        } finally {
            if (!shell.isDisposed()) shell.dispose();
            if (!display.isDisposed()) display.dispose();
        }
    }
}
