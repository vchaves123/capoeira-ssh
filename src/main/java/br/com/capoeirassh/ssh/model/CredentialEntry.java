package br.com.capoeirassh.ssh.model;

import java.util.UUID;

public class CredentialEntry {

    public String id       = UUID.randomUUID().toString();
    public String label    = "";
    public String username = "";
    /** Password for user/password auth, or the private key's passphrase when keyPath is set. */
    public char[] password = new char[0];
    /** Empty = password credential; non-empty = private-key credential (path to the key file). */
    public String keyPath  = "";

    /** Deep copy, including a fresh password array — the caller can freely mutate the result
     *  (e.g. while editing in a dialog) without touching the original's fields or its char[]. */
    public CredentialEntry copy() {
        CredentialEntry c = new CredentialEntry();
        c.id       = id;
        c.label    = label;
        c.username = username;
        c.password = password != null ? java.util.Arrays.copyOf(password, password.length) : new char[0];
        c.keyPath  = keyPath;
        return c;
    }

    @Override
    public String toString() {
        return label.isBlank() ? username : label;
    }
}
