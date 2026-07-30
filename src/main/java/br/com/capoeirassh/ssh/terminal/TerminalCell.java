package br.com.capoeirassh.ssh.terminal;

public final class TerminalCell {

    /** Unicode code point, not a UTF-16 unit — an {@code int} so that characters outside the
     *  BMP (emoji, and the U+F0000 private-use range newer Nerd Fonts put their icons in) can
     *  be stored instead of being replaced by '?'. */
    public int     character = ' ';
    public int     fgColor   = TerminalEmulator.DEFAULT_COLOR;
    public int     bgColor   = TerminalEmulator.DEFAULT_COLOR;
    public boolean bold      = false;
    public boolean underline = false;
    public boolean reverse   = false;
    public boolean blink     = false;
    /** True for the second cell of a double-width character (CJK, emoji): it holds no
     *  character of its own, it only reserves the column the wide glyph spills into. The
     *  renderer must skip it (the wide glyph is drawn from the first cell) — see
     *  {@link TerminalEmulator#charWidth}. */
    public boolean wideTrailer = false;

    public TerminalCell() {}

    public TerminalCell(TerminalCell src) { copyFrom(src); }

    public void copyFrom(TerminalCell src) {
        this.character   = src.character;
        this.fgColor     = src.fgColor;
        this.bgColor     = src.bgColor;
        this.bold        = src.bold;
        this.underline   = src.underline;
        this.reverse     = src.reverse;
        this.blink       = src.blink;
        this.wideTrailer = src.wideTrailer;
    }

    public void resetAttrs() {
        fgColor     = TerminalEmulator.DEFAULT_COLOR;
        bgColor     = TerminalEmulator.DEFAULT_COLOR;
        bold        = false;
        underline   = false;
        reverse     = false;
        blink       = false;
        wideTrailer = false;
    }

    public void clear() {
        character = ' ';
        resetAttrs();
    }
}
