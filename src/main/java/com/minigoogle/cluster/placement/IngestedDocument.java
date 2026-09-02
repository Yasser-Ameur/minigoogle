package com.minigoogle.cluster.placement;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A crawled document as it travels through document placement: to a peer
 * that owns it on the consistent hash ring, or back out for a repair.
 *
 * <p>Mirrors {@link com.minigoogle.crawler.model.ParsedDocument} field for
 * field, so the composition root can convert either way without loss:
 * {@code new IngestedDocument(doc.id(), doc.url(), doc.title(), doc.text(),
 * doc.outgoingLinks(), doc.crawlTime())} and back.
 */
public record IngestedDocument(
        UUID id,
        URI url,
        String title,
        String text,
        List<URI> outgoingLinks,
        Instant crawlTime
) {
}
