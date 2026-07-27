package com.minigoogle.crawler.parser;

import com.minigoogle.crawler.model.DownloadedPage;
import com.minigoogle.crawler.model.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for JSoup HTML parser functionality. */
class JSoupHtmlParserTest {

    private final JSoupHtmlParser parser = new JSoupHtmlParser();

    @Test
    void testParseBasicHtml() {
        String html = "<html><head><title>Test Page</title></head>" +
                      "<body><p>Hello World</p><a href='/link1'>Link</a></body></html>";
        
        DownloadedPage page = new DownloadedPage(
            URI.create("https://example.com/page"),
            200,
            html,
            Map.of(),
            Instant.now()
        );

        Optional<ParsedDocument> result = parser.parse(page);
        
        assertTrue(result.isPresent());
        ParsedDocument doc = result.get();
        assertEquals("Test Page", doc.title());
        assertEquals("Hello World Link", doc.text());
        assertEquals(1, doc.outgoingLinks().size());
        assertEquals("https://example.com/link1", doc.outgoingLinks().get(0).toString());
    }
}
