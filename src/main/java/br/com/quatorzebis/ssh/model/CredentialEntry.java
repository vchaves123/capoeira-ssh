package br.com.quatorzebis.ssh.model;

import java.util.UUID;

public class CredentialEntry {

    public String id       = UUID.randomUUID().toString();
    public String label    = "";
    public String username = "";
    public String password = "";

    @Override
    public String toString() {
        return label.isBlank() ? username : label;
    }
}
