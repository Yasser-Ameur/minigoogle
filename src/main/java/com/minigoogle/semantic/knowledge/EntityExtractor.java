package com.minigoogle.semantic.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts proper-noun entities from document titles and text.
 *
 * <p>Entities are capitalized multi-word phrases (up to four words) such as
 * "James Gosling" or "Java Virtual Machine". Detection is deterministic: a
 * phrase must consist of capitalized words, must not begin with a common
 * sentence word, and matches are counted with title occurrences weighted
 * higher than body occurrences. Results are ordered by count descending and
 * then alphabetically, capped at {@code maxEntitiesPerDoc}.</p>
 */
public final class EntityExtractor {

    private static final Pattern PHRASE_PATTERN = Pattern.compile(
            "\\b([A-Z][a-zA-Z0-9]*)(?:[- ][A-Z][a-zA-Z0-9]*){0,3}\\b");

    private static final Set<String> SKIP_WORDS = Set.of(
            "A", "An", "The", "This", "These", "Those", "That", "It", "He",
            "She", "They", "We", "You", "I", "And", "But", "Or", "However",
            "Furthermore", "Additionally", "In", "On", "At", "For", "By",
            "With", "From", "As", "If", "Then", "There", "Their", "Our",
            "Your", "Its", "Who", "What", "When", "Where", "Why", "How",
            "Not", "No", "Yes", "All", "Some", "Most", "Many", "Each",
            "Every", "Both", "One", "Two");

    private final int maxEntitiesPerDoc;

    /**
     * Creates an extractor keeping up to 10 entities per document.
     */
    public EntityExtractor() {
        this(10);
    }

    /**
     * Creates an extractor with a per-document entity cap.
     *
     * @param maxEntitiesPerDoc Maximum entities returned per document (>= 1).
     */
    public EntityExtractor(int maxEntitiesPerDoc) {
        if (maxEntitiesPerDoc < 1) {
            throw new IllegalArgumentException("maxEntitiesPerDoc must be >= 1");
        }
        this.maxEntitiesPerDoc = maxEntitiesPerDoc;
    }

    /**
     * Extracts and ranks entities from a document's title and text.
     *
     * @param title The document title (may be null).
     * @param text  The document body (may be null).
     * @return The ranked entity list, capped at {@code maxEntitiesPerDoc}.
     */
    public List<String> extract(String title, String text) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        countMatches(title, 3, counts);
        countMatches(text, 1, counts);

        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(counts.entrySet());
        ranked.sort(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()));

        List<String> entities = new ArrayList<>();
        for (Map.Entry<String, Integer> e : ranked) {
            if (entities.size() >= maxEntitiesPerDoc) {
                break;
            }
            entities.add(e.getKey());
        }
        return entities;
    }

    private void countMatches(String text, int weight, Map<String, Integer> counts) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Matcher matcher = PHRASE_PATTERN.matcher(text);
        while (matcher.find()) {
            String phrase = matcher.group();
            String normalized = normalize(phrase);
            if (normalized == null) {
                continue;
            }
            counts.merge(normalized, weight, Integer::sum);
        }
    }

    private String normalize(String phrase) {
        String trimmed = phrase.replaceAll("\\s+", " ").trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String firstWord = trimmed.split(" ")[0];
        if (SKIP_WORDS.contains(firstWord)) {
            return null;
        }
        return trimmed;
    }
}
