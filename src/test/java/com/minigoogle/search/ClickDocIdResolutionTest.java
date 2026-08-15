package com.minigoogle.search;

import com.minigoogle.core.config.Configuration;
import com.minigoogle.crawler.model.ParsedDocument;
import com.minigoogle.demo.DemoDocuments;
import com.minigoogle.ml.features.QueryDocumentFeatures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for click-feedback document resolution: the frontend reports
 * a clicked URL with no documentId, so the node must resolve the local document
 * id from the URL before feeding the click to the learning-to-rank trainer.
 */
class ClickDocIdResolutionTest {

    @TempDir
    Path tempDir;

    private SearchEngineBuild build() throws Exception {
        List<ParsedDocument> docs = DemoDocuments.all();
        Configuration config = new Configuration(Map.of(
                "semantic.enabled", "false",
                "semantic.hybrid.enabled", "false",
                "semantic.expansion.enabled", "true"));
        return SearchEngineBuilder.build(docs, config, tempDir);
    }

    @Test
    void urlToDocIdInvertsDocUrls() throws Exception {
        SearchEngineBuild build = build();

        assertEquals(build.docUrls().size(), build.urlToDocId().size(),
                "URL -> docId lookup must cover every indexed document");
        for (Map.Entry<String, Integer> e : build.urlToDocId().entrySet()) {
            assertEquals(e.getKey(), build.docUrls().get(e.getValue()),
                    "URL -> docId -> URL round trip must be identity");
        }
    }

    @Test
    void clickResolvedByUrlYieldsFeaturesForTraining() throws Exception {
        SearchEngineBuild build = build();
        String firstUrl = build.docUrls().values().iterator().next();
        int documentId = build.urlToDocId().get(firstUrl);

        assertTrue(documentId > 0, "A URL from docUrls must resolve to a positive docId");
        QueryDocumentFeatures features = build.featureExtractor().features("query", documentId);
        assertNotNull(features,
                "Click training must be able to resolve features for a URL-resolved docId");
    }
}
