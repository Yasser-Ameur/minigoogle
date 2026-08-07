package com.minigoogle.ml.click;

/**
 * A pairwise preference derived from click behavior: the {@code preferred}
 * document was clicked while the {@code nonPreferred} document was served
 * above it and not clicked.
 *
 * @param query           The query.
 * @param preferredDocId  The clicked document.
 * @param nonPreferredDocId A document served above the click that was ignored.
 */
public record ClickPreference(
        String query,
        int preferredDocId,
        int nonPreferredDocId
) {
}
