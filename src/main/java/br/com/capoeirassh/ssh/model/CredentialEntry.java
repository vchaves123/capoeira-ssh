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

    /** Zero the password array so it cannot be recovered from a heap dump. */
    public void clearPassword() { java.util.Arrays.fill(password, '\0'); }

    @Override
    public String toString() {
        return label.isBlank() ? username : label;
    }
}
