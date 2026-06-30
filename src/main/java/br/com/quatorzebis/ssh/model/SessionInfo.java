package br.com.quatorzebis.ssh.model;

import java.util.UUID;

public class SessionInfo {

    public enum AuthType { PASSWORD, PRIVATE_KEY, SAVED_CREDENTIAL }

    /** Unique identifier — used as file name on disk. */
    public String   id       = UUID.randomUUID().toString();
    public String   name     = "";
    public String   host     = "";
    public int      port     = 22;
    public String   username = "";
    public AuthType authType = AuthType.PASSWORD;
    public String   keyPath  = "";
    /** Empty string means root level (no group). */
    public String   group        = "";
    /** ID of a CredentialEntry in the vault; empty = no saved credential. */
    public String   credentialId = "";
    /** Terminal appearance — font size 0 means "use default". */
    public int      appearFontSize = 0;
    public int      appearFgR = 204, appearFgG = 204, appearFgB = 204;
    public int      appearBgR = 0,   appearBgG = 0,   appearBgB = 0;

    /** Label shown in the tab title and session tree. */
    public String label() {
        return name.isBlank() ? (username + "@" + host) : name;
    }

    public String fileName() {
        return id + ".session";
    }
}
