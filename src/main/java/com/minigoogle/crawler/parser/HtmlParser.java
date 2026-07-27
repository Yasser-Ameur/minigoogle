package com.minigoogle.crawler.parser;

import com.minigoogle.crawler.model.DownloadedPage;
import com.minigoogle.crawler.model.ParsedDocument;

import java.util.Optional;

/**
 * Responsible for parsing downloaded HTML pages.
 */
public interface HtmlParser {
    
    /**
     * Parses the HTML content of a downloaded page.
     *
     * @param page The downloaded page.
     * @return An Optional containing the ParsedDocument if successful, or empty if parsing failed.
     */
    Optional<ParsedDocument> parse(DownloadedPage page);
}
