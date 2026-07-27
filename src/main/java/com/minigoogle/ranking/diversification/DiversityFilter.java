package com.minigoogle.ranking.diversification;

import com.minigoogle.ranking.model.RankedDocument;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Limits consecutive results from the same domain to improve result diversity.
 *
 * Without diversification, a single authoritative domain (e.g. Wikipedia)
 * could dominate the entire first page of results.
 */
public class DiversityFilter {

    private final int maxConsecutivePerDomain;

    /**
     * @param maxConsecutivePerDomain Maximum number of consecutive results allowed from the same domain.
     */
    public DiversityFilter(int maxConsecutivePerDomain) {
        this.maxConsecutivePerDomain = maxConsecutivePerDomain;
    }

    /**
     * Creates a DiversityFilter with the default limit of 2 consecutive results per domain.
     */
    public DiversityFilter() {
        this(2);
    }

    /**
     * Re-orders the result list to enforce domain diversity.
     * Results that would exceed the consecutive limit are deferred and inserted later.
     *
     * @param results Ranked results sorted by score (best first).
     * @return Diversified result list.
     */
    public List<RankedDocument> diversify(List<RankedDocument> results) {
        if (results == null || results.size() <= maxConsecutivePerDomain) {
            return results != null ? results : List.of();
        }

        List<RankedDocument> diversified = new ArrayList<>();
        List<RankedDocument> deferred = new ArrayList<>();

        for (RankedDocument doc : results) {
            String domain = extractDomain(doc.url());
            int consecutiveCount = countTrailingConsecutive(diversified, domain);

            if (consecutiveCount < maxConsecutivePerDomain) {
                diversified.add(doc);
            } else {
                deferred.add(doc);
            }
        }

        // Append deferred results at the end
        diversified.addAll(deferred);
        return diversified;
    }

    private int countTrailingConsecutive(List<RankedDocument> list, String domain) {
        int count = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (extractDomain(list.get(i).url()).equals(domain)) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    private String extractDomain(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            return host != null ? host : url;
        } catch (Exception e) {
            return url;
        }
    }
}
