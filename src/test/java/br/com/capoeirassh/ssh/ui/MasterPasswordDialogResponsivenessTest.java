package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.storage.CredentialStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MasterPasswordDialog}'s OK button used to call {@code CredentialStore.create()}/
 * {@code unlock()} — PBKDF2 at 600,000 iterations — directly in its {@code SWT.Selection}
 * listener, on the UI thread, freezing the window for however long that derivation took.
 *
 * <p>This test drives the real dialog (a real, offscreen SWT {@code Display}/{@code Shell}) and
 * asserts a deterministic, hardware-independent invariant: immediately after the "Create" button's
 * {@code SWT.Selection} listener returns control (via {@code Button.notifyListeners}), the vault
 * must NOT be unlocked yet. If {@code create()} ran synchronously in that listener (the bug), it
 * would have fully completed by the time {@code notifyListeners()} returns, so the vault would
 * already be unlocked right there. With the fix (background thread + {@code asyncExec}), the
 * listener only starts a thread and returns immediately, so the vault is still locked at that
 * exact point — this holds regardless of how fast PBKDF2 happens to be on the test machine.
 * (An earlier version of this test tried to detect blocking via a UI-thread timer "heartbeat"
 * gap; that turned out to be unreliable — 600,000 iterations complete fast enough on modern
 * hardware that no gap was observable even with the bug present.)
 *
 * Same vault-safety mechanism as {@code CredentialStoreTest}: surefire redirects {@code user.home}
 * to {@code target/test-home} for the forked test JVM, verified before this class runs anything.
 */
@Tag("ui") // needs a real SWT Display — no display on the Linux/macOS CI runners
class MasterPasswordDialogResponsivenessTest {

    private static Path vaultPath;

    @BeforeAll
    static void verifyVaultIsRedirected() throws Exception {
        Field f = CredentialStore.class.getDeclaredField("VAULT");
        f.setAccessible(true);
        vaultPath = (Path) f.get(null);
        String home = System.getProperty("user.home");
        Assumptions.assumeTrue(home != null && home.contains("test-home"),
                "user.home was not redirected to a test directory (got: " + home + ") — refusing "
              + "to run a test that creates a real vault. Run via `mvn test` (surefire applies "
              + "the redirect).");
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

    private static Shell findShellByText(Display display, String text) {
        for (Shell s : display.getShells()) {
            if (text.equals(s.getText())) return s;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Control> List<T> findAll(Composite root, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Control c : root.getChildren()) {
            if (type.isInstance(c)) found.add((T) c);
            if (c instanceof Composite child) found.addAll(findAll(child, type));
        }
        return found;
    }

    @Test
    @Timeout(30)
    @DisplayName("clicking Create returns control to the UI thread before create() finishes, instead of blocking it")
    void creatingVault_doesNotBlockUiThread() throws Exception {
        Display display = new Display();
        Shell shell = new Shell(display);
        try {
            // One-shot driver: find the dialog once it's up, fill both password fields, click Create.
            display.timerExec(200, () -> {
                Shell dlgShell = findShellByText(display, "Create Credential Vault");
                assertNotNull(dlgShell, "dialog shell not found — check the exact title text");
                List<Text> passwordFields = findAll(dlgShell, Text.class).stream()
                        .filter(t -> (t.getStyle() & SWT.PASSWORD) != 0)
                        .toList();
                assertEquals(2, passwordFields.size(), "expected password + confirm fields in create mode");
                passwordFields.get(0).setText("test-password-123");
                passwordFields.get(1).setText("test-password-123");

                Button okButton = findAll(dlgShell, Button.class).stream()
                        .filter(b -> "Create".equals(b.getText()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Create button not found"));
                okButton.notifyListeners(SWT.Selection, new Event());

                assertFalse(CredentialStore.getInstance().isUnlocked(),
                        "vault was already unlocked immediately after the Create click returned — "
                      + "create() appears to have run synchronously on the UI thread instead of "
                      + "on a background thread");
            });

            MasterPasswordDialog dlg = new MasterPasswordDialog(shell);
            boolean result = dlg.open(); // blocks (its own nested event loop) until disposed

            assertTrue(result, "dialog should report success after a valid Create");
            assertTrue(CredentialStore.getInstance().isUnlocked(), "vault should be unlocked after create()");
        } finally {
            if (!shell.isDisposed()) shell.dispose();
            if (!display.isDisposed()) display.dispose();
        }
    }
}
