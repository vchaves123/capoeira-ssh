package br.com.capoeirassh.ssh;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks GitHub for a newer release than the one currently running.
 * Does a single anonymous, unauthenticated HTTPS GET — no personal data sent.
 */
public final class UpdateChecker {

    private static final String API_URL =
        "https://api.github.com/repos/vchaves123/capoeira-ssh/releases/latest";
    public static final String RELEASES_URL =
        "https://github.com/vchaves123/capoeira-ssh/releases/latest";

    private static final Pattern TAG_PATTERN = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"");

    private UpdateChecker() {}

    /**
     * Runs the check on a background thread and delivers the newer version
     * string (e.g. "1.0.5") to {@code onUpdateFound}, or nothing at all if
     * already up to date, offline, or the request fails for any reason.
     */
    public static void checkAsync(Consumer<String> onUpdateFound) {
        Thread t = new Thread(() -> {
            try {
                String latest = fetchLatestVersion();
                if (latest != null && isNewer(latest, BuildInfo.VERSION)) {
                    onUpdateFound.accept(latest);
                }
            } catch (Exception ignored) {
                // No internet, rate-limited, or malformed response — fail silently.
            }
        }, "update-check");
        t.setDaemon(true);
        t.start();
    }

    private static String fetchLatestVersion() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(API_URL).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setConnectTimeout(4000);
        conn.setReadTimeout(4000);
        try (InputStream in = conn.getInputStream()) {
            // Bound the read: a MITM/compromised endpoint must not be able to OOM this thread
            // with an arbitrarily large body. The tag_name sits near the top of the JSON.
            byte[] body = in.readNBytes(64 * 1024);
            Matcher m = TAG_PATTERN.matcher(new String(body, StandardCharsets.UTF_8));
            return m.find() ? m.group(1) : null;
        } finally {
            conn.disconnect();
        }
    }

    /** Compares dotted numeric versions, e.g. "1.0.10" > "1.0.9". */
    static boolean isNewer(String candidate, String current) {
        String[] a = candidate.split("\\.");
        String[] b = current.split("\\.");
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int va = i < a.length ? parseIntSafe(a[i]) : 0;
            int vb = i < b.length ? parseIntSafe(b[i]) : 0;
            if (va != vb) return va > vb;
        }
        return false;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
