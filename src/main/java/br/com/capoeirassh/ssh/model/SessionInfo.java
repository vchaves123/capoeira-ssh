package br.com.capoeirassh.ssh.model;

import java.util.UUID;

public class SessionInfo {

    public enum AuthType { PASSWORD, PRIVATE_KEY, SAVED_CREDENTIAL }

    /** SSH: a network shell over jsch. SERIAL: a local RS232 connection over jSerialComm — no
     *  host/port/auth, just a COM port and line settings (see the serial* fields below). */
    public enum ConnectionType { SSH, SERIAL }
    public enum SerialParity { NONE, ODD, EVEN, MARK, SPACE }
    public enum SerialFlowControl { NONE, RTS_CTS, XON_XOFF }

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
    /** When true, the remote may switch the terminal between 80 and 132 columns with DECCOLM
     *  (ESC[?3l / ESC[?3h), which resizes the application window. xterm's {@code allowC132}
     *  equivalent — off there by default, on here, since a full-screen app asking for 132
     *  columns and silently getting 80 renders corrupted output instead of failing visibly. */
    public boolean  allowColumnMode = true;
    /** Manual display order in the flat sessions list (List view) — lower sorts first.
     *  Sessions sharing the default 0 fall back to their on-disk load order until the user
     *  drags one, at which point the whole visible list is resequenced to 0..N-1. */
    public int      sortOrder = 0;
    /** Free-form labels for filtering/organizing, independent of group. Capped at 6. */
    public java.util.List<String> tags = new java.util.ArrayList<>();

    // -----------------------------------------------------------------------
    // Serial (RS232) — only meaningful when connectionType == SERIAL
    // -----------------------------------------------------------------------
    public ConnectionType connectionType = ConnectionType.SSH;
    /** e.g. "COM3" (Windows) or "/dev/ttyUSB0" (Linux/macOS). */
    public String            serialPortName    = "";
    public int               serialBaudRate    = 9600;
    public int               serialDataBits    = 8;
    public SerialParity      serialParity      = SerialParity.NONE;
    /** 1 or 2 stop bits. */
    public int               serialStopBits    = 1;
    public SerialFlowControl serialFlowControl = SerialFlowControl.NONE;
    /** Serial devices typically don't echo what's typed the way a remote shell's PTY does —
     *  off by default; the user turns it on for a device that stays silent while typing. */
    public boolean           serialLocalEcho   = false;

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
        c.allowColumnMode = allowColumnMode;
        c.sortOrder = sortOrder;
        c.tags = new java.util.ArrayList<>(tags);
        c.connectionType     = connectionType;
        c.serialPortName     = serialPortName;
        c.serialBaudRate     = serialBaudRate;
        c.serialDataBits     = serialDataBits;
        c.serialParity       = serialParity;
        c.serialStopBits     = serialStopBits;
        c.serialFlowControl  = serialFlowControl;
        c.serialLocalEcho    = serialLocalEcho;
        return c;
    }

    /** Label shown in the tab title and session tree. */
    public String label() {
        if (name.isBlank())
            return connectionType == ConnectionType.SERIAL ? connectionSummary() : (username + "@" + host);
        return name;
    }

    /** Short "where this connects to" string — "host:port" for SSH (port omitted when 22), or
     *  "COM3 @ 9600" for a serial session. Used wherever the UI shows a session's target
     *  alongside its display name (tree tooltips, list rows) instead of duplicating the
     *  per-connection-type formatting at each call site. */
    public String connectionSummary() {
        if (connectionType == ConnectionType.SERIAL)
            return serialPortName.isBlank() ? "" : serialPortName + " @ " + serialBaudRate;
        return host + (port != 22 ? ":" + port : "");
    }

    public String fileName() {
        return id + ".session";
    }
}
