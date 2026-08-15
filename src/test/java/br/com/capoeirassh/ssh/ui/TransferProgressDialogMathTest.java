package br.com.capoeirassh.ssh.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code TransferProgressDialog}'s bar-fill and byte-formatting arithmetic, extracted to
 * package-private static methods so they're testable without a {@code Display}/SWT dependency —
 * same convention as {@code ImportSessionsDialogDedupeTest}.
 */
class TransferProgressDialogMathTest {

    // ── ratio ────────────────────────────────────────────────────────────────

    @Test
    void ratio_halfway_isOneHalf() {
        assertEquals(0.5, TransferProgressDialog.ratio(50, 100), 1e-9);
    }

    @Test
    void ratio_zeroDone_isZero() {
        assertEquals(0.0, TransferProgressDialog.ratio(0, 100), 1e-9);
    }

    @Test
    void ratio_fullyDone_isOne() {
        assertEquals(1.0, TransferProgressDialog.ratio(100, 100), 1e-9);
    }

    @Test
    void ratio_isClampedAtOne_evenIfDoneOvershootsTotal() {
        // count() reports raw byte deltas from JSch; a monitor bug or a size that changed
        // mid-transfer could in principle overshoot — the bar must not render past 100%.
        assertEquals(1.0, TransferProgressDialog.ratio(150, 100), 1e-9);
    }

    @Test
    void ratio_zeroTotal_isTreatedAsOneToAvoidDivideByZero() {
        assertEquals(0.0, TransferProgressDialog.ratio(0, 0), 1e-9);
    }

    // ── humanSize ────────────────────────────────────────────────────────────

    @Test
    void humanSize_belowOneKilobyte_isPlainBytes() {
        assertEquals("0 B", TransferProgressDialog.humanSize(0));
        assertEquals("1023 B", TransferProgressDialog.humanSize(1023));
    }

    @Test
    void humanSize_exactKilobyte() {
        assertEquals("1.0 KB", TransferProgressDialog.humanSize(1024));
    }

    @Test
    void humanSize_exactMegabyte() {
        // Same floating-point-exponent regression guard as RemoteFileBrowserDialogPathTest —
        // this class duplicates the formatting logic rather than sharing it.
        assertEquals("1.0 MB", TransferProgressDialog.humanSize(1024L * 1024));
    }

    @Test
    void humanSize_exactGigabyte() {
        assertEquals("1.0 GB", TransferProgressDialog.humanSize(1024L * 1024 * 1024));
    }
}
