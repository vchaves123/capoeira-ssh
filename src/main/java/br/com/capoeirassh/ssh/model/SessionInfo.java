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
    /** SessionIconType key (e.g. "linux"); empty = no icon, fall back to letter avatar. */
    public String   iconType     = "";
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
    /** When true, the SSH handshake/negotiation is shown live in the terminal (like {@code ssh -vvv}). */
    public boolean  sshVerbose = false;
    /** Manual display order in the flat sessions list (List view) — lower sorts first.
     *  Sessions sharing the default 0 fall back to their on-disk load order until the user
     *  drags one, at which point the whole visible list is resequenced to 0..N-1. */
    public int      sortOrder = 0;
    /** Free-form labels for filtering/organizing, independent of group. Capped at 6. */
    public java.util.List<String> tags = new java.util.ArrayList<>();

    /** Deep copy (tags gets its own list, not a shared reference). Used wherever code needs to
     *  stage in-progress edits without touching the original — e.g. SessionDialog mutates a
     *  copy while editing, only applying it back to the live/cached instance once
     *  SessionStorage.save() actually succeeds. */
    public SessionInfo copy() {
        SessionInfo c = new SessionInfo();
        c.id = id;
        c.name = name;
        c.host = host;
        c.port = port;
        c.username = username;
        c.authType = authType;
        c.keyPath = keyPath;
        c.group = group;
        c.iconType = iconType;
        c.credentialId = credentialId;
        c.appearFontSize = appearFontSize;
        c.appearFontName = appearFontName;
        c.appearFgR = appearFgR; c.appearFgG = appearFgG; c.appearFgB = appearFgB;
        c.appearBgR = appearBgR; c.appearBgG = appearBgG; c.appearBgB = appearBgB;
        c.logEnabled = logEnabled;
        c.logDir = logDir;
        c.logFileName = logFileName;
        c.terminalType = terminalType;
        c.backspaceCode = backspaceCode;
        c.sshVerbose = sshVerbose;
        c.sortOrder = sortOrder;
        c.tags = new java.util.ArrayList<>(tags);
        return c;
    }

    /** Label shown in the tab title and session tree. */
    public String label() {
        return name.isBlank() ? (username + "@" + host) : name;
    }

    public String fileName() {
        return id + ".session";
    }
}
