package br.com.capoeirassh.ssh.storage;

import org.junit.jupiter.api.Assumptions;
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

    private static boolean FileSystems_supportsPosix() {
        return java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }
}
