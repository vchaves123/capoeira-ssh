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

    // ── PickedFile.name() ────────────────────────────────────────────────────
    // Regression coverage for a path-traversal fix: the remote filename this is built from
    // (ChannelSftp.LsEntry.getFilename()) comes straight off the SFTP wire with no validation by
    // the protocol, so a malicious/compromised server fully controls it — name() must strip on
    // BOTH '/' and '\', not just '/', the same way BackupBundle.fromProps()'s basename extraction
    // already does, or a backslash-laden entry name (a valid path separator on Windows) survives
    // into MainWindow.downloadFiles()'s local destination path unstripped.

    @Test
    void name_ordinaryFile_isBasename() {
        assertEquals("report.txt", new RemoteFileBrowserDialog.PickedFile("/home/user/report.txt", 0).name());
    }

    @Test
    void name_noSlash_isWholeString() {
        assertEquals("report.txt", new RemoteFileBrowserDialog.PickedFile("report.txt", 0).name());
    }

    @Test
    void name_serverSuppliedBackslashTraversal_isStrippedToBasename() {
        // A hostile/compromised server's ls() entry name can be an arbitrary string containing
        // backslashes with no slash at all — lastIndexOf('/') alone would return the whole thing.
        String malicious = "/remote/dir/..\\..\\..\\AppData\\Roaming\\Startup\\evil.exe";
        assertEquals("evil.exe", new RemoteFileBrowserDialog.PickedFile(malicious, 0).name());
    }

    @Test
    void name_backslashOnlyNoSlash_isStrippedToBasename() {
        assertEquals("evil.exe",
            new RemoteFileBrowserDialog.PickedFile("..\\..\\..\\evil.exe", 0).name());
    }

    // ── sanitizeDisplayName ──────────────────────────────────────────────────
    // Trojan-Source-style display-spoofing guard: a malicious/compromised SFTP server fully
    // controls ChannelSftp.LsEntry.getFilename(), so bidi-override/zero-width characters embedded
    // there must never survive into what the user is shown (or into a downloaded local filename).
    // The deceptive characters below are the real raw Unicode code points (RLO, zero-width
    // space/joiners, BOM), not display artifacts — this file is UTF-8, same as the rest of the
    // codebase; see each test for which exact character it targets.

    @Test
    void sanitizeDisplayName_ordinaryName_isUnchanged() {
        assertEquals("report.txt", RemoteFileBrowserDialog.sanitizeDisplayName("report.txt"));
    }

    @Test
    void sanitizeDisplayName_nullOrEmpty_isEmptyString() {
        assertEquals("", RemoteFileBrowserDialog.sanitizeDisplayName(null));
        assertEquals("", RemoteFileBrowserDialog.sanitizeDisplayName(""));
    }

    @Test
    void sanitizeDisplayName_rightToLeftOverride_isStripped() {
        // U+202E (RLO) is the classic Trojan-Source character: it flips the visual reading order
        // of everything after it, e.g. disguising "gpj.exe" as "exe.jpg" when rendered.
        String malicious = "doc‮gpj.exe";
        String sanitized = RemoteFileBrowserDialog.sanitizeDisplayName(malicious);
        assertFalse(sanitized.contains("‮"));
        assertEquals("docgpj.exe", sanitized);
    }

    @Test
    void sanitizeDisplayName_zeroWidthCharacters_areStripped() {
        // U+200B (zero-width space), U+200C (ZWNJ), U+200D (ZWJ).
        String malicious = "report​‌‍.txt";
        assertEquals("report.txt", RemoteFileBrowserDialog.sanitizeDisplayName(malicious));
    }

    @Test
    void sanitizeDisplayName_bomCharacter_isStripped() {
        assertEquals("report.txt", RemoteFileBrowserDialog.sanitizeDisplayName("﻿report.txt"));
    }

    @Test
    void sanitizeDisplayName_otherControlCharacters_areStripped() {
        // U+0007 (BEL) — an ordinary ISO control character, not a bidi/zero-width one, covered by
        // the same sweep for defense in depth.
        assertEquals("report.txt", RemoteFileBrowserDialog.sanitizeDisplayName("report.txt"));
    }

    @Test
    void name_stripsDeceptiveCharactersFromServerSuppliedEntry() {
        // The same sanitization must also apply to the local download filename, not just the
        // table display — PickedFile.name() is what MainWindow.downloadFiles() actually writes
        // to local disk.
        String malicious = "/remote/dir/doc‮gpj.exe";
        assertEquals("docgpj.exe", new RemoteFileBrowserDialog.PickedFile(malicious, 0).name());
    }
}
