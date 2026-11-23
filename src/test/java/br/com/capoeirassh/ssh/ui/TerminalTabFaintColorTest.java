package br.com.capoeirassh.ssh.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The colour math behind SGR 2 (faint): {@link TerminalTab#blendHalf}. */
class TerminalTabFaintColorTest {

    @Test
    @DisplayName("Light grey on black dims to mid grey")
    void greyOnBlack() {
        assertEquals(0x666666, TerminalTab.blendHalf(0xCCCCCC, 0x000000));
    }

    @Test
    @DisplayName("Each channel is averaged on its own")
    void channelsIndependent() {
        assertEquals(0x7F7F00, TerminalTab.blendHalf(0xFF0000, 0x00FF00));
        assertEquals(0x80_40_20, TerminalTab.blendHalf(0x80_40_20, 0x80_40_20));
    }

    @Test
    @DisplayName("Never produces the -1 'default colour' sentinel or bits above 24")
    void staysIn24Bits() {
        int c = TerminalTab.blendHalf(0xFFFFFF, 0xFFFFFF);
        assertEquals(0xFFFFFF, c);
        assertEquals(0, c & 0xFF000000);
    }
}
