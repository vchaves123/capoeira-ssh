package br.com.capoeirassh.ssh.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code RemoteFileBrowserDialog}'s path-arithmetic and byte-formatting helpers, extracted to
 * package-private static methods so they're testable without a {@code Display}/SWT dependency —
 * same convention as {@code ImportSessionsDialogDedupeTest}.
 */
class RemoteFileBrowserDialogPathTest {

    // ── parentOf ─────────────────────────────────────────────────────────────

    @Test
    void parentOf_root_isItself() {
        assertEquals("/", RemoteFileBrowserDialog.parentOf("/"));
    }

    @Test
    void parentOf_nullOrEmpty_isRoot() {
        assertEquals("/", RemoteFileBrowserDialog.parentOf(null));
        assertEquals("/", RemoteFileBrowserDialog.parentOf(""));
    }

    @Test
    void parentOf_topLevelDir_isRoot() {
        assertEquals("/", RemoteFileBrowserDialog.parentOf("/home"));
    }

    @Test
    void parentOf_nestedDir_stripsLastSegment() {
        assertEquals("/home", RemoteFileBrowserDialog.parentOf("/home/user"));
    }

    @Test
    void parentOf_trailingSlash_isIgnored() {
        assertEquals("/home", RemoteFileBrowserDialog.parentOf("/home/user/"));
    }

    // ── joinPath ─────────────────────────────────────────────────────────────

    @Test
    void joinPath_dirWithoutTrailingSlash_insertsOne() {
        assertEquals("/home/user", RemoteFileBrowserDialog.joinPath("/home", "user"));
    }

    @Test
    void joinPath_dirWithTrailingSlash_doesNotDouble() {
        assertEquals("/home/user", RemoteFileBrowserDialog.joinPath("/home/", "user"));
    }

    @Test
    void joinPath_root_producesSingleSlash() {
        assertEquals("/etc", RemoteFileBrowserDialog.joinPath("/", "etc"));
    }

    // ── humanSize ────────────────────────────────────────────────────────────

    @Test
    void humanSize_belowOneKilobyte_isPlainBytes() {
        assertEquals("0 B", RemoteFileBrowserDialog.humanSize(0));
        assertEquals("1023 B", RemoteFileBrowserDialog.humanSize(1023));
    }

    @Test
    void humanSize_exactKilobyte() {
        assertEquals("1.0 KB", RemoteFileBrowserDialog.humanSize(1024));
    }

    @Test
    void humanSize_fractionalKilobyte() {
        assertEquals("1.5 KB", RemoteFileBrowserDialog.humanSize(1536));
    }

    @Test
    void humanSize_exactMegabyte() {
        // Regression guard: Math.log(1048576)/Math.log(1024) can land a hair under 2.0 due to
        // floating-point error, which would truncate the exponent to 1 and misreport this as
        // "1024.0 KB" instead of "1.0 MB".
        assertEquals("1.0 MB", RemoteFileBrowserDialog.humanSize(1024L * 1024));
    }

    @Test
    void humanSize_exactGigabyte() {
        assertEquals("1.0 GB", RemoteFileBrowserDialog.humanSize(1024L * 1024 * 1024));
    }
}
