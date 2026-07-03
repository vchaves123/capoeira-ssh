package br.com.capoeirassh.ssh.model;

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
    /** Empty string means "use default". */
    public String   appearFontName = "";
    public int      appearFgR = 204, appearFgG = 204, appearFgB = 204;
    public int      appearBgR = 0,   appearBgG = 0,   appearBgB = 0;
    /** Session logging. */
    public boolean  logEnabled  = false;
    public String   logDir      = "";
    public String   logFileName = "";
    /** PTY terminal type sent to the SSH server (e.g. "xterm-256color", "xterm" for AIX). */
    public String   terminalType  = "xterm-256color";
    /** Byte sent for the Backspace key: 0x7F (DEL, most systems) or 0x08 (BS, e.g. AIX). */
    public int      backspaceCode = 0x7F;

    /** Label shown in the tab title and session tree. */
    public String label() {
        return name.isBlank() ? (username + "@" + host) : name;
    }

    public String fileName() {
        return id + ".session";
    }
}
