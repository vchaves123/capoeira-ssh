package br.com.capoeirassh.ssh.terminal;

public final class TerminalCell {

    public char    character = ' ';
    public int     fgColor   = TerminalEmulator.DEFAULT_COLOR;
    public int     bgColor   = TerminalEmulator.DEFAULT_COLOR;
    public boolean bold      = false;
    public boolean underline = false;
    public boolean reverse   = false;
    public boolean blink     = false;

    public TerminalCell() {}

    public TerminalCell(TerminalCell src) { copyFrom(src); }

    public void copyFrom(TerminalCell src) {
        this.character = src.character;
        this.fgColor   = src.fgColor;
        this.bgColor   = src.bgColor;
        this.bold      = src.bold;
        this.underline = src.underline;
        this.reverse   = src.reverse;
        this.blink     = src.blink;
    }

    public void resetAttrs() {
        fgColor   = TerminalEmulator.DEFAULT_COLOR;
        bgColor   = TerminalEmulator.DEFAULT_COLOR;
        bold      = false;
        underline = false;
        reverse   = false;
        blink     = false;
    }

    public void clear() {
        character = ' ';
        resetAttrs();
    }
}
