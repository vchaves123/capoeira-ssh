package br.com.capoeirassh.ssh.model;

import java.util.UUID;

public class CredentialEntry {

    public String id       = UUID.randomUUID().toString();
    public String label    = "";
    public String username = "";
    /** Password for user/password auth, or the private key's passphrase when keyPath is set.
     *  Unused (stays empty) when kdbxFilePath is set — see below. */
    public char[] password = new char[0];
    /** Empty = password credential; non-empty = private-key credential (path to the key file).
     *  Mutually exclusive with kdbxFilePath. */
    public String keyPath  = "";
    /** Empty = a normal password/private-key credential (the common case). Non-empty = this
     *  entry is a reference to an entry in an external KeePass (.kdbx) file, imported via
     *  KdbxImportDialog — the password is never copied into this vault; {@link #password}
     *  stays empty and the live password is fetched on demand from the referenced file via
     *  KdbxSubprocessClient (see that class for why). Mutually exclusive with keyPath. */
    public String kdbxFilePath  = "";
    /** UUID (KeePass's own, stable across renames) of the entry within kdbxFilePath. Only
     *  meaningful when kdbxFilePath is set. */
    public String kdbxEntryUuid = "";

    public boolean isKdbxReference() { return kdbxFilePath != null && !kdbxFilePath.isBlank(); }

    /** Deep copy, including a fresh password array — the caller can freely mutate the result
     *  (e.g. while editing in a dialog) without touching the original's fields or its char[]. */
    public CredentialEntry copy() {
        CredentialEntry c = new CredentialEntry();
        c.id       = id;
        c.label    = label;
        c.username = username;
        c.password = password != null ? java.util.Arrays.copyOf(password, password.length) : new char[0];
        c.keyPath  = keyPath;
        c.kdbxFilePath  = kdbxFilePath;
        c.kdbxEntryUuid = kdbxEntryUuid;
        return c;
    }

    @Override
    public String toString() {
        return label.isBlank() ? username : label;
    }
}
