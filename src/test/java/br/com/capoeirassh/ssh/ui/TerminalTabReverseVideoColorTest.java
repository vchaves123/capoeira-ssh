package br.com.capoeirassh.ssh.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code TerminalTab.render()} used to swap a cell's fg/bg for reverse video (SGR 7) by simply
 * exchanging whatever two ints it had — but {@code TerminalEmulator.resolveColor()} returns -1
 * for "the terminal default", and swapping -1 with -1 is a no-op. So a cell that was reverse-video
 * with both colours still at their defaults (the common case: {@code tput rev}, status bars,
 * vttest's "negative") rendered identically to normal text (build 230's bug).
 *
 * <p>The fix — resolve the -1 sentinel to the real default RGB before swapping — was extracted
 * into the package-private, static {@link TerminalTab#swapForReverseVideo} specifically so this
 * colour math can be tested directly, without needing a live SWT {@code Display}/{@code Shell}
 * to construct a real {@code TerminalTab}.
 */
class TerminalTabReverseVideoColorTest {

    private static final int DEFAULT_FG = 0xCCCCCC; // e.g. Ouro/light-grey default foreground
    private static final int DEFAULT_BG = 0x000000; // default background: black

    @Test
    @DisplayName("Reverse video with both colours still at their default sentinel resolves to the real defaults, swapped")
    void bothDefault_resolvesAndSwapsRealDefaults() {
        int[] result = TerminalTab.swapForReverseVideo(-1, -1, DEFAULT_FG, DEFAULT_BG);
        assertEquals(DEFAULT_BG, result[0], "reversed foreground must become the real default background");
        assertEquals(DEFAULT_FG, result[1], "reversed background must become the real default foreground");
        assertNotEquals(-1, result[0], "must never leave the -1 sentinel unresolved after a reverse swap");
        assertNotEquals(-1, result[1]);
    }

    @Test
    @DisplayName("Reverse video with an explicit (non-default) fg/bg simply swaps them, no resolution needed")
    void explicitColors_simpleSwap() {
        int[] result = TerminalTab.swapForReverseVideo(0xFF0000, 0x00FF00, DEFAULT_FG, DEFAULT_BG);
        assertEquals(0x00FF00, result[0]);
        assertEquals(0xFF0000, result[1]);
    }

    @Test
    @DisplayName("Reverse video with only the foreground at default resolves just that side before swapping")
    void onlyForegroundDefault_resolvesJustThatSide() {
        int[] result = TerminalTab.swapForReverseVideo(-1, 0x00FF00, DEFAULT_FG, DEFAULT_BG);
        assertEquals(0x00FF00, result[0], "reversed foreground is the explicit background, unchanged");
        assertEquals(DEFAULT_FG, result[1], "reversed background must resolve the default foreground sentinel");
    }
}
