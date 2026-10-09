package br.com.capoeirassh.ssh.ssh;

import br.com.capoeirassh.ssh.model.SessionInfo;
import br.com.capoeirassh.ssh.model.TunnelSpec;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import org.eclipse.swt.widgets.Display;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A standalone SSH connection that carries a session's port-forwards (local {@code -L} and remote
 * {@code -R}). Same isolation model as {@link SftpConnection}: its own {@code Session}, its own
 * TCP connection — closing, disconnecting or reconnecting the terminal tab never touches it, and
 * stopping the tunnels never touches the terminal.
 *
 * <p>Threading: the state readers ({@link #isConnected}, {@link #isActive}, {@link #errorOf},
 * {@link #activeCount}) never block — the UI thread polls them while a start/stop/connect is in
 * flight on a worker. That matters for {@link #connect}: the host-key confirmation dialog it can
 * raise runs on the UI thread, so the UI thread must never wait on a lock {@code connect} holds.
 * Mutating calls are serialized with {@code ops}; the readers use only concurrent collections and a
 * volatile session reference.
 */
public class TunnelConnection {

    private final Object ops = new Object();
    private volatile Session session;
    private final Set<String>         active = ConcurrentHashMap.newKeySet();
    private final Map<String, String> errors = new ConcurrentHashMap<>();

    /** Opens the SSH session. See {@link SftpConnection#connect} for the credential semantics. */
    public void connect(SessionInfo info, char[] password, Display display) throws Exception {
        close();
        Session s = SshSessions.open(info, password, display); // may block on UI (host-key dialog): no lock held
        synchronized (ops) {
            session = s;
        }
    }

    public boolean isConnected() {
        Session s = session;
        return s != null && s.isConnected();
    }

    /** Starts one tunnel. Throws on failure (and records the reason, see {@link #errorOf}). */
    public void start(TunnelSpec t) throws JSchException {
        String key = t.key();
        synchronized (ops) {
            Session s = session;
            if (s == null || !s.isConnected()) {
                active.clear();
                errors.put(key, "Not connected");
                throw new JSchException("Not connected");
            }
            if (active.contains(key)) return;
            try {
                if (t.type == TunnelSpec.Type.LOCAL) {
                    s.setPortForwardingL(t.bindHost.trim(), t.bindPort, t.destHost.trim(), t.destPort);
                } else {
                    s.setPortForwardingR(remoteBind(t.bindHost), t.bindPort, t.destHost.trim(), t.destPort);
                }
                active.add(key);
                errors.remove(key);
            } catch (JSchException ex) {
                errors.put(key, friendly(ex));
                throw ex;
            }
        }
    }

    /** Stops one tunnel; a no-op if it isn't running. */
    public void stop(TunnelSpec t) {
        String key = t.key();
        synchronized (ops) {
            errors.remove(key);
            Session s = session;
            if (!active.remove(key) || s == null) return;
            try {
                if (t.type == TunnelSpec.Type.LOCAL) s.delPortForwardingL(t.bindHost.trim(), t.bindPort);
                else                                 s.delPortForwardingR(remoteBind(t.bindHost), t.bindPort);
            } catch (Exception ignored) {
                // Session already dead — nothing left to unbind.
            }
        }
    }

    /** True only while the tunnel is running AND the underlying session is still up. */
    public boolean isActive(TunnelSpec t) {
        return isConnected() && active.contains(t.key());
    }

    public int activeCount() {
        return isConnected() ? active.size() : 0;
    }

    /** Last start failure for this tunnel (e.g. "Port already in use"), or null. */
    public String errorOf(TunnelSpec t) {
        return errors.get(t.key());
    }

    /** Stops every tunnel and drops the SSH session. Safe to call repeatedly / when never connected. */
    public void close() {
        Session s;
        synchronized (ops) {
            active.clear();
            errors.clear();
            s = session;
            session = null;
        }
        if (s != null) {
            try { s.disconnect(); } catch (Exception ignored) {}
        }
    }

    /** OpenSSH's convention for a loopback-only remote listener is "localhost"; an empty bind means
     *  every interface (still subject to the server's GatewayPorts). */
    static String remoteBind(String bindHost) {
        String h = bindHost == null ? "" : bindHost.trim();
        if (h.equals("127.0.0.1") || h.equals("::1") || h.equals("[::1]")) return "localhost";
        return h;
    }

    static String friendly(JSchException ex) {
        String m = ex.getMessage() == null ? "" : ex.getMessage();
        String lower = m.toLowerCase();
        if (lower.contains("address already in use") || lower.contains("bind"))
            return "Port already in use";
        if (lower.contains("remote port forwarding") || lower.contains("forwarding failed")
                || lower.contains("tcpip-forward"))
            return "Server refused the remote forward (AllowTcpForwarding / port in use?)";
        return m.isEmpty() ? "Failed to start" : m;
    }
}
