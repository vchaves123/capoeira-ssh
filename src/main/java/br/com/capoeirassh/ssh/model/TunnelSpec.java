package br.com.capoeirassh.ssh.model;

import java.util.List;
import java.util.Objects;

/**
 * One saved SSH port-forward belonging to a session.
 *
 * <p>The two fields pairs read the same way for both kinds — "listen here, forward there" — only
 * the machine that listens and the machine that resolves the destination swap:
 * <ul>
 *   <li>{@link Type#LOCAL} ({@code ssh -L}): <em>this PC</em> listens on {@code bindHost:bindPort};
 *       connections are carried through the SSH server to {@code destHost:destPort}, resolved from
 *       the server's point of view.</li>
 *   <li>{@link Type#REMOTE} ({@code ssh -R}): the <em>SSH server</em> listens on
 *       {@code bindHost:bindPort}; connections are carried back to {@code destHost:destPort},
 *       resolved from this PC's point of view.</li>
 * </ul>
 */
public final class TunnelSpec {

    public enum Type { LOCAL, REMOTE }

    /** Hard cap on tunnels per session — also the bound SessionStorage enforces when loading, so a
     *  corrupted/hand-edited *.session file can't make the UI build an unbounded list. */
    public static final int MAX_PER_SESSION = 32;

    public Type    type        = Type.LOCAL;
    public String  bindHost    = "127.0.0.1";
    public int     bindPort    = 0;
    public String  destHost    = "localhost";
    public int     destPort    = 0;
    public String  description = "";
    /** Start this tunnel automatically right after the session's tab opens (only honoured when the
     *  session's credentials resolve without a prompt — see MainWindow). */
    public boolean autoStart   = false;

    public TunnelSpec() {}

    public TunnelSpec copy() {
        TunnelSpec c = new TunnelSpec();
        c.type = type; c.bindHost = bindHost; c.bindPort = bindPort;
        c.destHost = destHost; c.destPort = destPort;
        c.description = description; c.autoStart = autoStart;
        return c;
    }

    /** Identity used to track a running tunnel: two tunnels of the same type can never share a
     *  bind port on the same side, so type + bind port is unique within a session. */
    public String key() {
        return type.name() + ":" + bindPort;
    }

    /** "127.0.0.1:5432 → db.internal:5432" — the bind side is where the tunnel listens. */
    public String summary() {
        return bindHost + ":" + bindPort + " → " + destHost + ":" + destPort;
    }

    public boolean isLoopbackBind() {
        String h = bindHost == null ? "" : bindHost.trim().toLowerCase();
        return h.equals("127.0.0.1") || h.equals("localhost") || h.equals("::1") || h.equals("[::1]");
    }

    /** Null when valid, otherwise a short human-readable reason. */
    public String validate() {
        if (type == null) return "Type is required.";
        if (!isPortValid(bindPort)) return "Listen port must be between 1 and 65535.";
        if (!isPortValid(destPort)) return "Destination port must be between 1 and 65535.";
        if (!isHostSafe(bindHost)) return "Listen address is invalid.";
        if (destHost == null || destHost.isBlank() || !isHostSafe(destHost))
            return "Destination host is invalid.";
        return null;
    }

    /** Validates a whole list: each entry individually, plus no two tunnels sharing {@link #key()}. */
    public static String validateAll(List<TunnelSpec> specs) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (TunnelSpec t : specs) {
            String err = t.validate();
            if (err != null) return err;
            if (!seen.add(t.key()))
                return "Two " + (t.type == Type.LOCAL ? "local" : "remote")
                    + " tunnels listen on port " + t.bindPort + ".";
        }
        return null;
    }

    public static boolean isPortValid(int p) { return p >= 1 && p <= 65535; }

    /** Accepts hostnames, IPv4 and IPv6 (bracketed or not). Rejects whitespace/control characters
     *  and anything that could confuse the SSH forwarding request. Empty bind is allowed (it means
     *  "all interfaces" for a remote bind). */
    private static boolean isHostSafe(String h) {
        if (h == null) return false;
        h = h.trim();
        if (h.isEmpty()) return true;
        if (h.length() > 253) return false;
        return h.matches("[A-Za-z0-9._:\\[\\]*-]+");
    }

    @Override public boolean equals(Object o) {
        return o instanceof TunnelSpec t
            && type == t.type && bindPort == t.bindPort && destPort == t.destPort
            && autoStart == t.autoStart
            && Objects.equals(bindHost, t.bindHost) && Objects.equals(destHost, t.destHost)
            && Objects.equals(description, t.description);
    }
    @Override public int hashCode() {
        return Objects.hash(type, bindHost, bindPort, destHost, destPort, description, autoStart);
    }
}
