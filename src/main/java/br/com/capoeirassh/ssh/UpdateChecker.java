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
    private static final String FALLBACK_URL =
        "https://github.com/vchaves123/capoeira-ssh/releases/latest";

    private static final Pattern TAG_PATTERN  = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"");
    private static final Pattern URL_PATTERN  = Pattern.compile("\"html_url\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern BODY_PATTERN = Pattern.compile("\"body\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

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

            Matcher mv = TAG_PATTERN.matcher(json);
            if (!mv.find()) return null;
            String version = mv.group(1);

            Matcher mu = URL_PATTERN.matcher(json);
            String url = mu.find() ? mu.group(1) : FALLBACK_URL;

            Matcher mb = BODY_PATTERN.matcher(json);
            String notes = mb.find() ? unescapeJson(mb.group(1)) : "";

            return new UpdateInfo(version, url, notes);
        } finally {
            conn.disconnect();
        }
    }

    /** Minimal JSON string-escape decoder — just enough for GitHub's release body/URL fields. */
    private static String unescapeJson(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'u' -> {
                        if (i + 4 < s.length()) {
                            try {
                                out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
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
        return out.toString();
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
