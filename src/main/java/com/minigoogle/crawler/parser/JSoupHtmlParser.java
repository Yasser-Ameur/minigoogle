package com.minigoogle.crawler.parser;

import com.minigoogle.crawler.model.DownloadedPage;
import com.minigoogle.crawler.model.ParsedDocument;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JSoup-based HTML parser extracting links, title, and body content.
 * Resolves relative URLs to absolute form and produces a {@link ParsedDocument}
 * suitable for downstream indexing and link discovery.
 */
public class JSoupHtmlParser implements HtmlParser {

    @Override
    public Optional<ParsedDocument> parse(DownloadedPage page) {
        if (page == null || page.html() == null || page.html().isBlank()) {
            return Optional.empty();
        }

        try {
            Document doc = Jsoup.parse(page.html(), page.uri().toString());
            
            String title = doc.title();
            String text = doc.body() != null ? doc.body().text() : "";
            
            Elements links = doc.select("a[href]");
            List<URI> outgoingLinks = new ArrayList<>();
            
            for (Element link : links) {
                String absHref = link.attr("abs:href"); // Use JSoup's absolute URL resolution
                if (!absHref.isBlank()) {
                    try {
                        outgoingLinks.add(new URI(absHref));
                    } catch (URISyntaxException ignored) {
                        // Ignore malformed links
                    }
                }
            }

            ParsedDocument parsedDoc = new ParsedDocument(
                UUID.randomUUID(),
                page.uri(),
                title,
                text,
                outgoingLinks,
                Instant.now()
            );

            return Optional.of(parsedDoc);
        } catch (Exception e) {
            // Document might be malformed or something else went wrong
            return Optional.empty();
        }
    }
}
