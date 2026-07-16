package br.com.capoeirassh.ssh;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Checks GitHub for a newer release than the one currently running.
 * Does a single anonymous, unauthenticated HTTPS GET — no personal data sent.
 */
public final class UpdateChecker {

    private static final String API_URL =
        "https://api.github.com/repos/vchaves123/capoeira-ssh/releases/latest";
    private static final String FALLBACK_URL =
        "https://github.com/vchaves123/capoeira-ssh/releases/latest";

    /** Bound the read: a MITM/compromised endpoint must not be able to OOM this thread. */
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private UpdateChecker() {}

    /** Everything the UI needs to show an "update available" prompt. */
    public record UpdateInfo(String version, String releaseUrl, String releaseNotes) {}

    /**
     * Runs the check on a background thread and delivers the newer release's
     * info to {@code onUpdateFound}, or nothing at all if already up to date,
     * offline, disabled by the user, or the request fails for any reason.
     */
    public static void checkAsync(Consumer<UpdateInfo> onUpdateFound) {
        if (br.com.capoeirassh.ssh.storage.UiState.isUpdateAlertsDisabled()) return;
        Thread t = new Thread(() -> {
            try {
                UpdateInfo info = fetchLatestRelease();
                if (info != null && isNewer(info.version(), BuildInfo.VERSION)) {
                    onUpdateFound.accept(info);
                }
            } catch (Exception ignored) {
                // No internet, rate-limited, or malformed response — fail silently.
            }
        }, "update-check");
        t.setDaemon(true);
        t.start();
    }

    private static UpdateInfo fetchLatestRelease() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(API_URL).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setConnectTimeout(4000);
        conn.setReadTimeout(4000);
        try (InputStream in = conn.getInputStream()) {
            byte[] raw = in.readNBytes(MAX_RESPONSE_BYTES);
            String json = new String(raw, StandardCharsets.UTF_8);

            String tag = extractJsonString(json, "tag_name");
            if (tag == null) return null;
            String version = tag.startsWith("v") ? tag.substring(1) : tag;

            String url   = extractJsonString(json, "html_url");
            String notes = extractJsonString(json, "body");

            return new UpdateInfo(version, url != null ? url : FALLBACK_URL, notes != null ? notes : "");
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Finds {@code "key": "value"} in a JSON document and returns the decoded value, or null
     * if not found / unterminated. Scans character-by-character rather than matching a regex
     * against the (potentially several-KB) value — Java's {@code Pattern} implements a
     * repeated group like {@code (?:\\.|[^"])*} recursively, one stack frame per matched
     * character, so applying that shape to a normal-sized release-notes body reliably threw
     * {@code StackOverflowError} on the background update-check thread.
     */
    private static String extractJsonString(String json, String key) {
        String marker = "\"" + key + "\"";
        int idx = json.indexOf(marker);
        if (idx < 0) return null;
        idx = json.indexOf(':', idx + marker.length());
        if (idx < 0) return null;
        idx = json.indexOf('"', idx + 1);
        if (idx < 0) return null;

        StringBuilder out = new StringBuilder();
        for (int i = idx + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') return out.toString();
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                switch (next) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'u' -> {
                        if (i + 4 < json.length()) {
                            try {
                                out.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                    default -> out.append(next);
                }
            } else {
                out.append(c);
            }
        }
        return null; // unterminated — truncated by MAX_RESPONSE_BYTES or malformed
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
