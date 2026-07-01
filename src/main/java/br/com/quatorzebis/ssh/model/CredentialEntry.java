package br.com.quatorzebis.ssh.model;

import java.util.UUID;

public class CredentialEntry {

    public String id       = UUID.randomUUID().toString();
    public String label    = "";
    public String username = "";
    public char[] password = new char[0];

    /** Zero the password array so it cannot be recovered from a heap dump. */
    public void clearPassword() { java.util.Arrays.fill(password, '\0'); }

    @Override
    public String toString() {
        return label.isBlank() ? username : label;
    }
}
