package br.com.capoeirassh.ssh.ui;

import java.util.regex.Pattern;

/**
 * Renders a GitHub release's markdown body as a small, self-contained HTML document for
 * display in an SWT {@link org.eclipse.swt.browser.Browser}.
 *
 * The whole source is HTML-escaped up front, and only our own whitelisted tags (h1-h3, strong,
 * em, code, a, ul/li, table) are ever introduced afterward — so raw HTML or a script tag
 * embedded in a release body can never reach the page unescaped, even though the content
 * ultimately comes from a GitHub release (not fully trusted: a compromised repo/release could
 * otherwise inject markup that runs in the embedded webview).
 */
final class ReleaseNotesHtml {

    private ReleaseNotesHtml() {}

    private static final Pattern BOLD  = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern CODE  = Pattern.compile("`([^`]+)`");
    private static final Pattern LINK  = Pattern.compile("\\[([^\\]]+)]\\((https?://[^\\s)]+)\\)");
    private static final Pattern ITALIC = Pattern.compile("(?<!\\*)\\*([^*]+)\\*(?!\\*)");

    static String render(String markdown) {
        StringBuilder body = new StringBuilder();
        String[] lines = (markdown == null ? "" : markdown).replace("\r\n", "\n").split("\n", -1);

        boolean inList = false;
        int i = 0;
        while (i < lines.length) {
            String raw = lines[i];
            String line = escapeHtml(raw);
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                if (inList) { body.append("</ul>\n"); inList = false; }
                i++;
                continue;
            }

            // Table: a pipe row followed by a "|---|---|" separator row.
            if (looksLikeTableRow(trimmed) && i + 1 < lines.length && isTableSeparator(lines[i + 1])) {
                if (inList) { body.append("</ul>\n"); inList = false; }
                i = renderTable(lines, i, body);
                continue;
            }

            int hashes = leadingHashes(trimmed);
            if (hashes > 0) {
                if (inList) { body.append("</ul>\n"); inList = false; }
                int level = Math.min(3, hashes);
                body.append("<h").append(level).append('>')
                    .append(inlineFormat(trimmed.substring(hashes + 1)))
                    .append("</h").append(level).append(">\n");
                i++;
                continue;
            }

            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                if (!inList) { body.append("<ul>\n"); inList = true; }
                body.append("<li>").append(inlineFormat(trimmed.substring(2))).append("</li>\n");
                i++;
                continue;
            }

            if (inList) { body.append("</ul>\n"); inList = false; }
            body.append("<p>").append(inlineFormat(trimmed)).append("</p>\n");
            i++;
        }
        if (inList) body.append("</ul>\n");

        return "<!doctype html><html><head><meta charset=\"utf-8\"><style>" + CSS + "</style></head>"
            + "<body>" + body + "</body></html>";
    }

    /** Renders the header/separator/body rows starting at {@code start}, returns the next
     *  unconsumed line index. */
    private static int renderTable(String[] lines, int start, StringBuilder out) {
        out.append("<table>\n<thead><tr>");
        for (String cell : tableCells(escapeHtml(lines[start].trim()))) {
            out.append("<th>").append(inlineFormat(cell)).append("</th>");
        }
        out.append("</tr></thead>\n<tbody>\n");

        int i = start + 2; // skip header + separator
        while (i < lines.length && looksLikeTableRow(lines[i].trim())) {
            out.append("<tr>");
            for (String cell : tableCells(escapeHtml(lines[i].trim()))) {
                out.append("<td>").append(inlineFormat(cell)).append("</td>");
            }
            out.append("</tr>\n");
            i++;
        }
        out.append("</tbody>\n</table>\n");
        return i;
    }

    private static boolean looksLikeTableRow(String trimmedLine) {
        return trimmedLine.startsWith("|") && trimmedLine.endsWith("|") && trimmedLine.length() > 1;
    }

    private static boolean isTableSeparator(String line) {
        String t = line.trim();
        if (!looksLikeTableRow(t)) return false;
        return t.chars().allMatch(c -> c == '|' || c == '-' || c == ':' || c == ' ');
    }

    private static String[] tableCells(String escapedTrimmedRow) {
        String inner = escapedTrimmedRow.substring(1, escapedTrimmedRow.length() - 1);
        return inner.split("\\|", -1);
    }

    /** Returns the number of leading '#' characters if the line is a valid markdown header
     *  (1-6 hashes followed by a space), else 0. */
    private static int leadingHashes(String trimmed) {
        int hashes = 0;
        while (hashes < trimmed.length() && trimmed.charAt(hashes) == '#') hashes++;
        if (hashes == 0 || hashes > 6 || hashes >= trimmed.length() || trimmed.charAt(hashes) != ' ') return 0;
        return hashes;
    }

    private static String inlineFormat(String escapedText) {
        String s = escapedText.trim();
        s = CODE.matcher(s).replaceAll("<code>$1</code>");
        s = LINK.matcher(s).replaceAll("<a href=\"$2\">$1</a>");
        s = BOLD.matcher(s).replaceAll("<strong>$1</strong>");
        s = ITALIC.matcher(s).replaceAll("<em>$1</em>");
        return s;
    }

    private static String escapeHtml(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static final String CSS =
        "body{font-family:Segoe UI,Arial,sans-serif;font-size:13px;color:#222;background:#fff;"
        + "margin:8px 12px;}"
        + "h1,h2,h3{margin:0.6em 0 0.3em;}"
        + "p{margin:0.4em 0;}"
        + "code{background:#f0f0f0;padding:1px 4px;border-radius:3px;font-family:Consolas,monospace;}"
        + "table{border-collapse:collapse;margin:0.5em 0;width:100%;}"
        + "th,td{border:1px solid #ccc;padding:4px 8px;text-align:left;}"
        + "th{background:#f5f5f5;}"
        + "ul{margin:0.3em 0;padding-left:1.4em;}"
        + "a{color:#1a5fb4;}";
}
