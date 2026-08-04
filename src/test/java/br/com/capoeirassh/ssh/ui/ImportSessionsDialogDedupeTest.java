package br.com.capoeirassh.ssh.ui;

import br.com.capoeirassh.ssh.model.SessionInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code ImportSessionsDialog}'s dedupe logic (used when re-scanning the same source, or
 * importing from a second source that overlaps with the first — the entire point of the build-
 * 263 background-threading fix for the PuTTY/MobaXterm scan buttons) had no test of its own.
 * Extracted to the package-private, static {@code isDuplicate()} so it's testable without
 * constructing the dialog's {@code Table} widget.
 */
class ImportSessionsDialogDedupeTest {

    private static SessionInfo session(String name, String host, int port, String username) {
        SessionInfo s = new SessionInfo();
        s.name = name; s.host = host; s.port = port; s.username = username;
        return s;
    }

    @Test
    void identicalSession_isADuplicate() {
        List<SessionInfo> found = new ArrayList<>(List.of(session("prod", "example.com", 22, "alice")));
        assertTrue(ImportSessionsDialog.isDuplicate(found, session("prod", "example.com", 22, "alice")));
    }

    @Test
    void hostAndUsernameComparisons_areCaseInsensitive() {
        List<SessionInfo> found = new ArrayList<>(List.of(session("prod", "Example.COM", 22, "Alice")));
        assertTrue(ImportSessionsDialog.isDuplicate(found, session("prod", "example.com", 22, "alice")));
    }

    @Test
    void nameComparison_isCaseSensitive() {
        List<SessionInfo> found = new ArrayList<>(List.of(session("Prod", "example.com", 22, "alice")));
        assertFalse(ImportSessionsDialog.isDuplicate(found, session("prod", "example.com", 22, "alice")),
                "unlike host/username, name is compared case-sensitively — this documents the actual "
              + "current behaviour, not necessarily an ideal one");
    }

    @Test
    void differentPort_isNotADuplicate() {
        List<SessionInfo> found = new ArrayList<>(List.of(session("prod", "example.com", 22, "alice")));
        assertFalse(ImportSessionsDialog.isDuplicate(found, session("prod", "example.com", 2222, "alice")));
    }

    @Test
    void differentHost_isNotADuplicate() {
        List<SessionInfo> found = new ArrayList<>(List.of(session("prod", "example.com", 22, "alice")));
        assertFalse(ImportSessionsDialog.isDuplicate(found, session("prod", "other.example.com", 22, "alice")));
    }

    @Test
    void differentUsername_isNotADuplicate() {
        List<SessionInfo> found = new ArrayList<>(List.of(session("prod", "example.com", 22, "alice")));
        assertFalse(ImportSessionsDialog.isDuplicate(found, session("prod", "example.com", 22, "bob")));
    }

    @Test
    void emptyFoundList_neverHasADuplicate() {
        assertFalse(ImportSessionsDialog.isDuplicate(new ArrayList<>(), session("prod", "example.com", 22, "alice")));
    }
}
