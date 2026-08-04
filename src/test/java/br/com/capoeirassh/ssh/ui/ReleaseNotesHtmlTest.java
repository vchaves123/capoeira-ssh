package br.com.capoeirassh.ssh.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code ReleaseNotesHtml.render()} turns a GitHub release body (untrusted content — a
 * compromised repo/release could contain raw HTML/script markup) into HTML shown in an embedded
 * {@code Browser}. Only the UI-level fallback around a hypothetical {@code render()} failure was
 * ever tested ({@code MainWindowNotesAreaFallbackTest}); the escaping/formatting logic itself,
 * which is what actually has to hold the line against an XSS-style payload, had no test.
 */
class ReleaseNotesHtmlTest {

    @Test
    void render_escapesAScriptTag_neverEmittedRaw() {
        String html = ReleaseNotesHtml.render("<script>alert(1)</script>");
        assertFalse(html.contains("<script>"), "a raw <script> tag must never reach the output HTML");
        assertTrue(html.contains("&lt;script&gt;"));
    }

    @Test
    void render_escapesAnHtmlAttributeInjectionAttempt() {
        // A release body trying to break out of an attribute via a crafted "link" URL.
        String html = ReleaseNotesHtml.render("[click](https://evil.example/\" onmouseover=\"alert(1))");
        assertFalse(html.contains("onmouseover=\"alert(1)\""),
                "a quote in a link URL must be escaped before ever reaching the href attribute, "
              + "not allowed to break out of it");
    }

    @Test
    void render_escapesAmpersandLtGtQuoteApostrophe() {
        String html = ReleaseNotesHtml.render("Tom & Jerry <b>bold</b> \"quoted\" 'apos'");
        assertTrue(html.contains("Tom &amp; Jerry"));
        assertTrue(html.contains("&lt;b&gt;bold&lt;/b&gt;"));
        assertTrue(html.contains("&quot;quoted&quot;"));
        assertTrue(html.contains("&#39;apos&#39;"));
    }

    @Test
    void render_boldItalicCodeAndLink_areFormattedCorrectly() {
        String html = ReleaseNotesHtml.render("**bold** *italic* `code` [text](https://example.com/path)");
        assertTrue(html.contains("<strong>bold</strong>"));
        assertTrue(html.contains("<em>italic</em>"));
        assertTrue(html.contains("<code>code</code>"));
        assertTrue(html.contains("<a href=\"https://example.com/path\">text</a>"));
    }

    @Test
    void render_headers_h1ThroughH6AllCapAtH3() {
        assertTrue(ReleaseNotesHtml.render("# Title").contains("<h1>Title</h1>"));
        assertTrue(ReleaseNotesHtml.render("## Title").contains("<h2>Title</h2>"));
        assertTrue(ReleaseNotesHtml.render("### Title").contains("<h3>Title</h3>"));
        assertTrue(ReleaseNotesHtml.render("#### Title").contains("<h3>Title</h3>"), "h4+ must cap at h3");
        assertTrue(ReleaseNotesHtml.render("###### Title").contains("<h3>Title</h3>"), "h6 must cap at h3");
    }

    @Test
    void render_aHashWithoutASpace_isNotTreatedAsAHeader() {
        String html = ReleaseNotesHtml.render("#nothashheader");
        assertFalse(html.contains("<h1>"));
        assertTrue(html.contains("<p>#nothashheader</p>"));
    }

    @Test
    void render_bulletList_wrapsItemsInUlLi() {
        String html = ReleaseNotesHtml.render("- first\n- second\n* third");
        assertTrue(html.contains("<ul>\n<li>first</li>\n<li>second</li>\n<li>third</li>\n</ul>"));
    }

    @Test
    void render_blankLine_closesAnOpenList() {
        String html = ReleaseNotesHtml.render("- item\n\nafter");
        int ulClose = html.indexOf("</ul>");
        int afterP  = html.indexOf("<p>after</p>");
        assertTrue(ulClose >= 0 && afterP > ulClose, "the list must close before the following paragraph");
    }

    @Test
    void render_table_rendersHeaderAndBodyRows() {
        String md = "| A | B |\n|---|---|\n| 1 | 2 |";
        String html = ReleaseNotesHtml.render(md);
        assertTrue(html.contains("<table>"));
        assertTrue(html.contains("<th>A</th>"));
        assertTrue(html.contains("<th>B</th>"));
        assertTrue(html.contains("<td>1</td>"));
        assertTrue(html.contains("<td>2</td>"));
    }

    @Test
    void render_nullMarkdown_doesNotThrow_producesEmptyBody() {
        String html = assertDoesNotThrow(() -> ReleaseNotesHtml.render(null));
        assertTrue(html.contains("<body></body>"));
    }

    @Test
    void render_emptyMarkdown_doesNotThrow() {
        assertDoesNotThrow(() -> ReleaseNotesHtml.render(""));
    }

    @Test
    void render_crLfLineEndings_areNormalized_notLeftAsStrayCharacters() {
        String html = ReleaseNotesHtml.render("line1\r\nline2");
        assertTrue(html.contains("<p>line1</p>"));
        assertTrue(html.contains("<p>line2</p>"));
    }
}
