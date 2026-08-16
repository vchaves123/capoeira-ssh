package br.com.capoeirassh.ssh.storage;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * July 2026 security audit, finding #18: on Windows, {@code SecureFiles.write()} must restrict a
 * sensitive file (vault, session files, logs) to the owner only via NTFS ACLs — {@code
 * java.io.File.setReadable/Writable(false, ...)} is a silent no-op against ACLs on Windows, so
 * {@code restrictWindows()} shells out to {@code icacls} instead ({@code /inheritance:r
 * /grant:r <owner>:F}). Never had a test actually reading the resulting ACL back and confirming
 * only the owner has an access entry — this is the one finding from that audit that can only be
 * verified for real on this project's actual Windows dev machine (every other storage test
 * targets the POSIX branch instead, which shows SKIPPED here).
 */
@Tag("ci-env") // assumes the dev machine's normal ACLs; GitHub-hosted runners add SYSTEM/Administrators
class SecureFilesWindowsAclTest {

    @Test
    void write_onWindows_restrictsAclToOwnerOnly() throws Exception {
        Assumptions.assumeTrue(!FileSystems_supportsPosix(),
                "this test targets the Windows ACL branch specifically");

        Path dir = Files.createTempDirectory("securefiles-acl-test");
        Path file = dir.resolve("secret.bin");

        SecureFiles.write(file, "sensitive".getBytes());

        AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
        assertNotNull(view, "AclFileAttributeView must be available on Windows");

        UserPrincipal owner = Files.getOwner(file);
        List<AclEntry> acl = view.getAcl();

        assertFalse(acl.isEmpty(), "restrictWindows() must have set an explicit ACL, not left it empty");
        for (AclEntry entry : acl) {
            assertEquals(owner, entry.principal(),
                    "every ACL entry must belong to the owner only — found an entry for "
                  + entry.principal() + ", meaning some other user/group still has access: " + acl);
        }
    }

    /**
     * {@code SecureFiles.createDirectories()} used to just delegate to {@code
     * Files.createDirectories(dir)} on Windows with no ACL restriction at all — only individual
     * files went through {@code restrictWindows()}, so a newly created directory under
     * {@code ~/.capoeira} (sessions, traces, etc.) simply inherited whatever ACL its parent
     * happened to have, unlike the POSIX branch, which always restricts every segment it creates.
     */
    @Test
    void createDirectories_onWindows_restrictsEachNewSegmentToOwnerOnly() throws Exception {
        Assumptions.assumeTrue(!FileSystems_supportsPosix(),
                "this test targets the Windows ACL branch specifically");

        Path base = Files.createTempDirectory("securefiles-dir-acl-test");
        Path newDir = base.resolve("capoeira-like").resolve("sessions");
        assertFalse(Files.exists(newDir), "test setup: directory must not pre-exist");

        SecureFiles.createDirectories(newDir);
        assertTrue(Files.exists(newDir), "createDirectories() must have created the target");

        // Check every segment THIS call created (base already existed and is left alone).
        for (Path dir : new Path[]{ base.resolve("capoeira-like"), newDir }) {
            AclFileAttributeView view = Files.getFileAttributeView(dir, AclFileAttributeView.class);
            assertNotNull(view, "AclFileAttributeView must be available on Windows");
            UserPrincipal owner = Files.getOwner(dir);
            List<AclEntry> acl = view.getAcl();
            assertFalse(acl.isEmpty(), "every newly created segment must have an explicit owner-only ACL: " + dir);
            for (AclEntry entry : acl) {
                assertEquals(owner, entry.principal(),
                        "every ACL entry on " + dir + " must belong to the owner only — found an entry for "
                      + entry.principal() + ": " + acl);
            }
        }
    }

    private static boolean FileSystems_supportsPosix() {
        return java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }
}
