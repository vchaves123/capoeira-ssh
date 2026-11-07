package br.com.capoeirassh.ssh.ssh;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchAlgoNegoFailException;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the legacy-algorithm fallback that lets Capoeira reach old network gear whose
 * firmware only ever supported algorithms modern SSH has since disabled.
 *
 * <p>Drives the real {@code SshConnection} helpers against a real jsch {@link Session}.
 * {@code JSch.getSession()} only builds the object — nothing is sent and no socket is opened —
 * so the configured proposals can be inspected exactly as they would be handed to a server.
 */
class SshConnectionLegacyAlgorithmsTest {

    private static Session newSession() throws JSchException {
        return new JSch().getSession("user", "example.invalid", 22);
    }

    private static List<String> algorithmsOf(Session session, String key) {
        return Arrays.asList(session.getConfig(key).split(","));
    }

    /**
     * jsch's {@code JSchAlgoNegoFailException} constructor is package-private, so a test outside
     * that package can only build one reflectively. Index 1 is {@code server_host_key} — the
     * category a legacy device actually fails on, since it offers only {@code ssh-rsa}.
     */
    private static JSchAlgoNegoFailException negotiationFailure(String serverProposal)
            throws Exception {
        Constructor<?> ctor = Class.forName("com.jcraft.jsch.JSchAlgoNegoFailException")
                .getDeclaredConstructor(int.class, String.class, String.class);
        ctor.setAccessible(true);
        return (JSchAlgoNegoFailException) ctor.newInstance(1, "our-proposal", serverProposal);
    }

    // ── appendAlgorithms ────────────────────────────────────────────────────

    @Test
    @DisplayName("legacy names are appended at the END, so modern algorithms keep priority")
    void appendsAtTheEnd_modernAlgorithmsKeepPriority() throws Exception {
        Session session = newSession();
        List<String> before = algorithmsOf(session, "server_host_key");

        SshConnection.appendAlgorithms(session, "server_host_key", "ssh-rsa");

        List<String> after = algorithmsOf(session, "server_host_key");
        assertEquals(before.get(0), after.get(0),
                "the strongest algorithm must still be proposed first — this is what makes "
              + "enabling the fallback safe: a server that supports something modern still "
              + "negotiates that, and only a server with nothing better reaches ssh-rsa");
        assertEquals("ssh-rsa", after.get(after.size() - 1),
                "ssh-rsa must be last, i.e. lowest preference");
        assertEquals(before.size() + 1, after.size());
        assertTrue(after.subList(0, before.size()).equals(before),
                "the existing proposal must be preserved verbatim, not rebuilt");
    }

    @Test
    @DisplayName("applying twice does not duplicate an algorithm")
    void isIdempotent() throws Exception {
        Session session = newSession();
        SshConnection.appendAlgorithms(session, "server_host_key", "ssh-rsa");
        List<String> once = algorithmsOf(session, "server_host_key");

        SshConnection.appendAlgorithms(session, "server_host_key", "ssh-rsa");

        assertEquals(once, algorithmsOf(session, "server_host_key"));
    }

    @Test
    @DisplayName("an algorithm already present in the defaults is not duplicated")
    void doesNotDuplicateAnAlgorithmAlreadyInTheDefaults() throws Exception {
        // Not hypothetical: hmac-sha1 ships in jsch's default MAC proposal, so the MAC category
        // of enableLegacyAlgorithms would double it up without the containment check.
        Session session = newSession();
        assertTrue(algorithmsOf(session, "mac.s2c").contains("hmac-sha1"),
                "test premise: jsch's default MAC proposal still includes hmac-sha1");

        SshConnection.appendAlgorithms(session, "mac.s2c", "hmac-sha1");

        assertEquals(1, algorithmsOf(session, "mac.s2c").stream()
                        .filter("hmac-sha1"::equals).count());
    }

    @Test
    @DisplayName("a config key this jsch version does not know is skipped, not invented")
    void unknownKeyIsSkipped() throws Exception {
        Session session = newSession();

        assertDoesNotThrow(() ->
                SshConnection.appendAlgorithms(session, "no.such.config.key", "ssh-rsa"));
        assertNull(session.getConfig("no.such.config.key"),
                "a renamed or removed key must degrade to 'legacy not enabled for that "
              + "category', never to a list fabricated by us");
    }

    // ── enableLegacyAlgorithms ──────────────────────────────────────────────

    @Test
    @DisplayName("enabling adds the ssh-rsa host key that legacy gear needs")
    void enablesTheHostKeyAlgorithmLegacyGearOffers() throws Exception {
        Session session = newSession();
        assertFalse(algorithmsOf(session, "server_host_key").contains("ssh-rsa"),
                "test premise: modern jsch does not propose ssh-rsa by default");

        SshConnection.enableLegacyAlgorithms(session);

        assertTrue(algorithmsOf(session, "server_host_key").contains("ssh-rsa"));
    }

    @Test
    @DisplayName("enabling also widens key exchange, ciphers and MACs")
    void widensTheOtherAlgorithmCategoriesToo() throws Exception {
        // Fixing only the host key would just move the failure to the next category on the same
        // device — these generations of firmware are usually old across the board.
        Session session = newSession();
        SshConnection.enableLegacyAlgorithms(session);

        assertTrue(algorithmsOf(session, "kex").contains("diffie-hellman-group1-sha1"));
        assertTrue(algorithmsOf(session, "cipher.s2c").contains("3des-cbc"));
        assertTrue(algorithmsOf(session, "cipher.c2s").contains("aes128-cbc"));
        assertTrue(algorithmsOf(session, "mac.s2c").contains("hmac-md5"));
    }

    @Test
    @DisplayName("a session left alone keeps the modern-only proposals")
    void doesNothingUnlessCalled() throws Exception {
        Session session = newSession();
        assertFalse(algorithmsOf(session, "server_host_key").contains("ssh-rsa"));
        assertFalse(algorithmsOf(session, "kex").contains("diffie-hellman-group1-sha1"));
    }

    // ── failure detection ───────────────────────────────────────────────────

    @Test
    @DisplayName("a negotiation failure is recognised")
    void recognisesANegotiationFailure() throws Exception {
        assertTrue(SshConnection.isAlgorithmNegotiationFailure(negotiationFailure("ssh-rsa")));
    }

    @Test
    @DisplayName("a negotiation failure is still recognised when wrapped in a cause chain")
    void recognisesAWrappedNegotiationFailure() throws Exception {
        // Guards the offer against a future jsch (or our own code) wrapping the throw site:
        // matching only the top-level type would silently stop offering the one fix that works.
        Throwable wrapped = new IOException("connect failed",
                new RuntimeException("inner", negotiationFailure("ssh-rsa")));

        assertTrue(SshConnection.isAlgorithmNegotiationFailure(wrapped));
    }

    @Test
    @DisplayName("an unrelated failure is not mistaken for a negotiation failure")
    void doesNotMisreadOtherFailures() {
        assertFalse(SshConnection.isAlgorithmNegotiationFailure(new JSchException("Auth fail")));
        assertFalse(SshConnection.isAlgorithmNegotiationFailure(new IOException("timed out")));
        assertFalse(SshConnection.isAlgorithmNegotiationFailure(null));
    }

    @Test
    @DisplayName("the description names the failing category and what the server offered")
    void describesTheFailureCompactly() throws Exception {
        String description =
                SshConnection.describeNegotiationFailure(negotiationFailure("ssh-rsa"));

        assertEquals("server_host_key: ssh-rsa", description,
                "the user-facing message shows this instead of jsch's own text, which embeds "
              + "both full proposals — hundreds of characters of algorithm names that say "
              + "nothing actionable");
    }

    @Test
    @DisplayName("describing an unrelated failure returns null")
    void describesNothingForUnrelatedFailures() {
        assertNull(SshConnection.describeNegotiationFailure(new JSchException("Auth fail")));
        assertNull(SshConnection.describeNegotiationFailure(null));
    }
}
