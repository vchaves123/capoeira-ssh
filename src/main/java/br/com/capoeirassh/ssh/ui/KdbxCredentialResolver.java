package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.model.CredentialEntry;
import br.com.capoeirassh.ssh.storage.CredentialStore;
import br.com.capoeirassh.ssh.storage.KdbxMasterPasswordCache;
import br.com.capoeirassh.ssh.storage.KdbxSubprocessClient;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * Resolves the live password for a {@link CredentialEntry} that is a KeePass reference
 * ({@link CredentialEntry#isKdbxReference()}).
 *
 * Two paths, chosen per-entry by whether the user opted in at import time
 * (KdbxImportDialog's "save this file's master password in the vault" checkbox):
 *
 * <ul>
 *   <li><b>Saved</b> ({@code ce.password} non-empty): the master password already lives in this
 *       app's own vault, exactly like any other stored credential — no prompt, no separate
 *       cache. If it turns out stale (the user changed the KeePass file's master password since
 *       import), this falls through to the prompt-and-heal path below and silently re-saves the
 *       corrected value, since the user already consented to persistent storage for this entry.</li>
 *   <li><b>Not saved</b> ({@code ce.password} empty — the user opted out, or the entry predates
 *       the checkbox): prompts every time, backed by a short {@link KdbxMasterPasswordCache}
 *       window so reconnecting to several sessions from the same file in a row doesn't reprompt
 *       for each one. Never silently starts persisting — that stays an explicit, opt-in choice.</li>
 * </ul>
 */
public final class KdbxCredentialResolver {

    private KdbxCredentialResolver() {}

    /**
     * @return the fetched password as a fresh char[] the caller owns and must zero after use,
     *         or null if the user cancelled the master-password prompt.
     * @throws Exception with a user-facing message on a broken reference or other unrecoverable
     *         failure — callers should show it to the user (e.g. a MessageBox) and treat it the
     *         same as a cancelled connection attempt.
     */
    public static char[] resolve(Shell owner, CredentialEntry ce) throws Exception {
        Path kdbxFile = Path.of(ce.kdbxFilePath);
        boolean hadStoredMaster = ce.password != null && ce.password.length > 0;

        if (hadStoredMaster) {
            char[] stored = Arrays.copyOf(ce.password, ce.password.length);
            try {
                return fetchWithIndicator(owner, kdbxFile, stored, ce.kdbxEntryUuid);
            } catch (KdbxSubprocessClient.KdbxException ex) {
                if (ex.reason != KdbxSubprocessClient.KdbxException.Reason.WRONG_PASSWORD) {
                    throw new Exception(ex.getMessage());
                }
                // Stored master password no longer opens the file — fall through to the
                // prompt-and-heal path below instead of failing outright.
            } finally {
                Arrays.fill(stored, '\0');
            }
        }

        KdbxMasterPasswordCache cache = KdbxMasterPasswordCache.getInstance();
        while (true) {
            char[] master = cache.get(kdbxFile);
            boolean fromCache = master != null;
            if (master == null) {
                master = new KdbxMasterPasswordPromptDialog(owner, kdbxFile).open();
                if (master == null) return null; // user cancelled
            }
            try {
                char[] pw = fetchWithIndicator(owner, kdbxFile, master, ce.kdbxEntryUuid);
                cache.put(kdbxFile, master); // success — (re)start the cache window
                if (hadStoredMaster) healStoredMasterPassword(ce, master);
                return pw;
            } catch (KdbxSubprocessClient.KdbxException ex) {
                if (ex.reason == KdbxSubprocessClient.KdbxException.Reason.WRONG_PASSWORD) {
                    if (fromCache) {
                        // The cached password no longer opens the file (changed externally, or
                        // this cache entry was stale) — drop it and reprompt, don't just fail.
                        cache.remove(kdbxFile);
                        continue;
                    }
                    alert(owner, "Wrong master password for \"" + kdbxFile.getFileName() + "\".");
                    continue;
                }
                // ENTRY_NOT_FOUND / FILE_NOT_FOUND / OTHER — not recoverable by retrying the
                // password, so surface it as a broken-reference failure instead of reprompting.
                throw new Exception(ex.getMessage());
            } finally {
                Arrays.fill(master, '\0');
            }
        }
    }

    /**
     * Runs the actual reader-subprocess call on a background thread while showing a small modal
     * "Unlocking…" indicator (see {@link BusyDialog}) — starting a JVM, opening the file, and
     * exiting typically takes well under a second, but with no feedback at all that reads as the
     * app hanging, especially the first time a user hits it.
     */
    private static char[] fetchWithIndicator(Shell owner, Path kdbxFile, char[] master, String entryUuid)
            throws Exception {
        return BusyDialog.run(owner, "KeePass",
            "Unlocking " + kdbxFile.getFileName() + "…\nThis may take a moment.",
            () -> KdbxSubprocessClient.fetchPassword(kdbxFile, master, entryUuid));
    }

    /** Re-saves a corrected master password into the vault for an entry that already had one
     *  stored — the user consented to persistent storage for this entry once already, so this
     *  runs silently rather than asking again every time the KeePass file's password changes.
     *  Best-effort: a failed re-save just means the next connect prompts again, not fatal.
     *
     *  <p>Backgrounded like every other {@code addOrUpdate()} call site (CredentialManagerDialog's
     *  {@code runPersist}, SessionDialog's "Save Credential" handler) — this method is invoked
     *  directly from {@link #resolve} on the UI thread (a credential-picker selection listener),
     *  and {@code addOrUpdate()}'s AES-GCM encrypt-and-rewrite of the whole vault is exactly the
     *  kind of disk I/O those other call sites deliberately keep off that thread. The defensive
     *  copy of {@code newMaster} is taken synchronously, before the background thread starts —
     *  {@link #resolve}'s own {@code finally} block zeroes its {@code master} array as soon as
     *  this method returns, which would otherwise race a still-running background read of it. */
    private static void healStoredMasterPassword(CredentialEntry ce, char[] newMaster) {
        char[] masterCopy = Arrays.copyOf(newMaster, newMaster.length);
        Thread t = new Thread(() -> {
            try {
                CredentialEntry updated = CredentialStore.getInstance().findById(ce.id);
                if (updated == null) return; // deleted from the vault meanwhile
                updated.password = masterCopy;
                CredentialStore.getInstance().addOrUpdate(updated);
            } catch (Exception ignored) {
                // Non-fatal — see method comment.
            }
        }, "kdbx-heal-master-password");
        t.setDaemon(true);
        t.start();
    }

    private static void alert(Shell parent, String msg) {
        MessageBox mb = new MessageBox(parent, SWT.ICON_ERROR | SWT.OK);
        mb.setText("KeePass");
        mb.setMessage(msg);
        mb.open();
    }
}
