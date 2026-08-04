package br.com.capoeirassh.ssh.ssh;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code SshConnection.SwtHostVerifier} shows a real, modal {@code MessageBox} in
 * {@code promptYesNo()} (via {@code display.syncExec}), which needs a live SWT {@code Display}
 * and blocks waiting for a user click — not something a unit test can safely drive. This test
 * instead exercises {@code isHostKeyChangedMessage(String)}, the pure detection logic
 * {@code promptYesNo()} extracted specifically to be testable this way, against the *exact*
 * message text JSch (com.github.mwiede:jsch 0.2.21, {@code Session#checkHost}) passes for each
 * of the two scenarios it's meant to tell apart.
 */
class SwtHostVerifierTest {

    private static boolean isHostKeyChangedMessage(String message) throws Exception {
        Class<?> verifierClass = Class.forName("br.com.capoeirassh.ssh.ssh.SshConnection$SwtHostVerifier");
        Method m = verifierClass.getDeclaredMethod("isHostKeyChangedMessage", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, message);
    }

    @Test
    @DisplayName("JSch's changed-host-key message is recognized as the MITM/changed-key case")
    void changedKeyMessage_isRecognized() throws Exception {
        String jschChangedKeyMessage =
                "WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!\n"
              + "IT IS POSSIBLE THAT SOMEONE IS DOING SOMETHING NASTY!\n"
              + "Someone could be eavesdropping on you right now (man-in-the-middle attack)!\n"
              + "It is also possible that a host key has just been changed.";
        assertTrue(isHostKeyChangedMessage(jschChangedKeyMessage));
    }

    @Test
    @DisplayName("JSch's unknown-host-key message is NOT recognized as the changed-key case")
    void unknownKeyMessage_isNotRecognized() throws Exception {
        String jschUnknownKeyMessage =
                "The authenticity of host 'example.com' can't be established.\n"
              + "RSA key fingerprint is SHA256:abc123.\n"
              + "Are you sure you want to continue connecting?";
        assertFalse(isHostKeyChangedMessage(jschUnknownKeyMessage));
    }

    @Test
    @DisplayName("detection is case-insensitive")
    void detection_isCaseInsensitive() throws Exception {
        assertTrue(isHostKeyChangedMessage("remote host identification has changed!"));
    }

    @Test
    @DisplayName("null message is treated as the benign (unknown-key) case, not the changed-key case")
    void nullMessage_isNotRecognizedAsChanged() throws Exception {
        assertFalse(isHostKeyChangedMessage(null));
    }
}
