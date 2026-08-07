package com.minigoogle.ml.impression;

import com.minigoogle.ml.features.NormalizationContext;
import com.minigoogle.ml.features.QueryDocumentFeatures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests that the coordinator resolves train-time features from served impressions. */
class ServedImpressionFeatureProviderTest {

    @Test
    void resolvesFeaturesFromServedImpressionAtPositionZero() {
        ImpressionLog log = new ImpressionLog();
        double[] raw = {0.8, 0.5, 0.4, 0.0, 0.9, 0.2, 100, 0.0};
        log.recordImpression(new ServedImpression("java", new NormalizationContext(1.0, 200),
                List.of(new ServedResult(7, "http://x/1", "t", "s", 0.9, 0.8, 0.5, raw))));

        ServedImpressionFeatureProvider provider = new ServedImpressionFeatureProvider(log);
        QueryDocumentFeatures features = provider.features("java", 7);

        assertNotNull(features);
        double[] values = features.values();
        assertEquals(0.8, values[0], 1e-9);
        assertEquals(0.5, values[1], 1e-9);
        assertEquals(0.4, values[2], 1e-9);
        assertEquals(0.9, values[4], 1e-9);
        assertEquals(0.2, values[5], 1e-9);
        // DOC_LENGTH is normalized against the impression's maxDocLength.
        assertEquals(Math.log1p(100) / Math.log1p(200), values[6], 1e-9);
        // Trained at position 0 so the model learns content preference, not rank bias.
        assertEquals(1.0, values[7], 1e-9);
    }

    @Test
    void returnsNullWhenQueryNotServedOrDocumentNotInImpression() {
        ImpressionLog log = new ImpressionLog();
        double[] raw = {0.8, 0.5, 0.4, 0.0, 0.9, 0.2, 100, 0.0};
        log.recordImpression(new ServedImpression("java", new NormalizationContext(1.0, 200),
                List.of(new ServedResult(7, "http://x/1", "t", "s", 0.9, 0.8, 0.5, raw))));
        ServedImpressionFeatureProvider provider = new ServedImpressionFeatureProvider(log);

        assertNull(provider.features("unseen", 7));
        assertNull(provider.features("java", 999));
    }

    @Test
    void returnsNullWhenServedWithoutRawFeatures() {
        ImpressionLog log = new ImpressionLog();
        log.recordImpression(new ServedImpression("java", new NormalizationContext(1.0, 200),
                List.of(new ServedResult(7, "http://x/1", "t", "s", 0.9, 0.8, 0.5, null))));
        ServedImpressionFeatureProvider provider = new ServedImpressionFeatureProvider(log);
        assertNull(provider.features("java", 7));
    }

    @Test
    void docIdRegistryAssignsStableGlobalIds() {
        DocIdRegistry registry = new DocIdRegistry();
        int a = registry.resolve("http://a.example.com/x");
        int b = registry.resolve("http://b.example.com/y");
        assertNotEquals(a, b);
        assertEquals(a, registry.resolve("http://a.example.com/x"));
        assertEquals("http://b.example.com/y", registry.url(b));
    }
}
