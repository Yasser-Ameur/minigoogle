package com.minigoogle.ml.impression;

import com.minigoogle.ml.features.NormalizationContext;

import java.util.List;

/**
 * The full state of one served search response on the coordinator: the ordered
 * results plus the global normalization context they were ranked against.
 *
 * <p>Storing the context alongside the results lets click-derived training
 * normalize the served raw features with the exact same corpus statistics that
 * produced the served ordering.</p>
 */
public record ServedImpression(
        String query,
        NormalizationContext context,
        List<ServedResult> results) {

    public ServedImpression {
        results = results == null ? List.of() : List.copyOf(results);
    }
}
