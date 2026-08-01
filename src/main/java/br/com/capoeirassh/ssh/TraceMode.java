package br.com.capoeirassh.ssh;

/** Global switch for the {@code --trace} command-line flag (see {@link Main#main}). Gates the
 *  buffer-dump/screenshot shortcut and per-connection packet tracing — all diagnostic-only
 *  features that are otherwise unavailable, since they write raw session content to disk. */
public final class TraceMode {

    public static volatile boolean enabled = false;

    private TraceMode() {}
}
