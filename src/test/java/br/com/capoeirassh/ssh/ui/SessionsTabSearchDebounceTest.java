package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.model.SessionInfo;
import br.com.capoeirassh.ssh.storage.SessionStorage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code SessionsTab}'s search field applied its O(N) {@code filterList()} (scans every row and
 * triggers a full layout pass) synchronously on every {@code SWT.Modify} event — once per
 * keystroke — so a large list re-scanned and re-laid-out itself on every character typed into a
 * multi-character query. The fix debounces it (same cancel-and-reschedule
 * {@code display.timerExec} pattern already used by {@code TerminalTab}'s resize handling):
 * apply the filter once, shortly after the user stops typing, instead of on every keystroke.
 *
 * <p>This is a behavioral change (deferred application), not a raw complexity change, so this
 * test asserts the deferral directly: right after {@code searchText.setText(...)} fires
 * {@code SWT.Modify}, a non-matching row must still be visible (the filter hasn't run yet); only
 * after pumping the event loop past the debounce delay does it become hidden.
 */
@Tag("ui") // needs a real SWT Display — no display on the Linux/macOS CI runners
class SessionsTabSearchDebounceTest {

    private static Path sessionsDir;

    @BeforeEach
    void verifyRedirectedAndClean() throws Exception {
        String home = System.getProperty("user.home");
        Assumptions.assumeTrue(home != null && home.contains("test-home"),
                "user.home was not redirected to a test directory (got: " + home + ") — refusing "
              + "to create real session files. Run via `mvn test`.");
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

    @SuppressWarnings("unchecked")
    private static <T> T getField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(target);
    }

    @Test
    @Timeout(30)
    void typingInSearchField_doesNotFilterImmediately_onlyAfterDebounceDelay() throws Exception {
        SessionInfo s = new SessionInfo();
        s.id   = UUID.randomUUID().toString();
        s.name = "session-alpha";
        s.host = "alpha.example.com";
        SessionStorage.save(s);

        Display display = new Display();
        Shell shell = new Shell(display);
        try {
            CTabFolder folder = new CTabFolder(shell, SWT.NONE);
            SessionsTab tab = new SessionsTab(folder, shell,
                    (info, pw) -> {}, () -> {}, () -> {}, java.util.Collections::emptySet);

            Text searchText = getField(tab, "searchText");
            Composite listContainer = getField(tab, "listContainer");
            Control row = listContainer.getChildren()[0];
            assertTrue(row.getVisible(), "test setup: row should start visible with no filter applied");

            // A query that matches nothing — fires SWT.Modify.
            searchText.setText("this-matches-nothing-xyz");

            // Immediately after, before pumping the event loop at all: the debounced filter must
            // NOT have run yet, so the row is still visible.
            assertTrue(row.getVisible(),
                    "row was already hidden immediately after setText() — filterList() appears to "
                  + "have run synchronously instead of being debounced");

            // Pump the event loop past the debounce delay so the deferred filter fires.
            long deadline = System.currentTimeMillis() + 2_000;
            while (row.getVisible() && System.currentTimeMillis() < deadline) {
                if (!display.readAndDispatch()) display.sleep();
            }

            assertFalse(row.getVisible(),
                    "row is still visible after waiting past the debounce delay — the deferred "
                  + "filter never applied");
        } finally {
            if (!shell.isDisposed()) shell.dispose();
            if (!display.isDisposed()) display.dispose();
        }
    }
}
