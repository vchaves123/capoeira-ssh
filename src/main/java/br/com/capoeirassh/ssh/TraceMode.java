package br.com.capoeirassh.ssh;

/** Global switch for the {@code --trace} command-line flag (see {@link Main#main}). Gates the
 *  buffer-dump/screenshot shortcut and per-connection packet tracing — all diagnostic-only
 *  features that are otherwise unavailable, since they write raw session content to disk. */
public final class TraceMode {

    public static volatile boolean enabled = false;

    /** Runtime on/off switch for RX/TX packet-trace logging, independent of {@link #enabled} —
     *  toggled live (Ctrl+Shift+P) instead of fixed for the process lifetime. Starts off even
     *  when {@code --trace} is passed; the user must explicitly turn capture on. */
    public static volatile boolean packetCaptureEnabled = false;

    private TraceMode() {}
}
