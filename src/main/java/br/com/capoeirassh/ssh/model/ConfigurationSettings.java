package br.com.capoeirassh.ssh.model;

/**
 * Logging / appearance / terminal-type / backspace-key settings shared by
 * {@code ConfigurationSettingsDialog} across its three scopes: global defaults,
 * a single session, or a single running terminal tab.
 */
public class ConfigurationSettings {

    public int     appearFontSize = 0;
    public String  appearFontName = "";
    public int     appearFgR = 204, appearFgG = 204, appearFgB = 204;
    public int     appearBgR = 0,   appearBgG = 0,   appearBgB = 0;

    public boolean logEnabled  = false;
    public String  logDir      = "";
    public String  logFileName = "";

    public String  terminalType  = "xterm-256color";
    public int     backspaceCode = 0x7F;

    /** When true, the SSH handshake/negotiation is shown live in the terminal (like {@code ssh -vvv}). */
    public boolean sshVerbose = false;

    public static ConfigurationSettings fromSession(SessionInfo s) {
        ConfigurationSettings c = new ConfigurationSettings();
        c.appearFontSize = s.appearFontSize;
        c.appearFontName = s.appearFontName;
        c.appearFgR = s.appearFgR; c.appearFgG = s.appearFgG; c.appearFgB = s.appearFgB;
        c.appearBgR = s.appearBgR; c.appearBgG = s.appearBgG; c.appearBgB = s.appearBgB;
        c.logEnabled  = s.logEnabled;
        c.logDir      = s.logDir;
        c.logFileName = s.logFileName;
        c.terminalType  = s.terminalType;
        c.backspaceCode = s.backspaceCode;
        c.sshVerbose    = s.sshVerbose;
        return c;
    }

    public void applyTo(SessionInfo s) {
        s.appearFontSize = appearFontSize;
        s.appearFontName = appearFontName;
        s.appearFgR = appearFgR; s.appearFgG = appearFgG; s.appearFgB = appearFgB;
        s.appearBgR = appearBgR; s.appearBgG = appearBgG; s.appearBgB = appearBgB;
        s.logEnabled  = logEnabled;
        s.logDir      = logDir;
        s.logFileName = logFileName;
        s.terminalType  = terminalType;
        s.backspaceCode = backspaceCode;
        s.sshVerbose    = sshVerbose;
    }

    public ConfigurationSettings copy() {
        ConfigurationSettings c = new ConfigurationSettings();
        c.appearFontSize = appearFontSize; c.appearFontName = appearFontName;
        c.appearFgR = appearFgR; c.appearFgG = appearFgG; c.appearFgB = appearFgB;
        c.appearBgR = appearBgR; c.appearBgG = appearBgG; c.appearBgB = appearBgB;
        c.logEnabled = logEnabled; c.logDir = logDir; c.logFileName = logFileName;
        c.terminalType = terminalType; c.backspaceCode = backspaceCode;
        c.sshVerbose = sshVerbose;
        return c;
    }
}
