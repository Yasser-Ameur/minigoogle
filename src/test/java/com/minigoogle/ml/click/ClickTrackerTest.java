package com.minigoogle.ml.click;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for click and impression tracking plus preference derivation. */
class ClickTrackerTest {

    @Test
    void testImpressionAndClickCounters() {
        ClickTracker tracker = new ClickTracker();
        tracker.recordImpression("java", List.of(1, 2, 3));

        assertEquals(3, tracker.impressionCount());
        assertEquals(1, tracker.impressions("java", 2));
        assertEquals(0, tracker.impressions("java", 99));
        assertEquals(0, tracker.clickCount());

        tracker.recordClick(new ClickEvent("java", 2, "http://example.com/2", 2));
        tracker.recordClick(new ClickEvent("JAVA", 3, "http://example.com/3", 3));

        assertEquals(2, tracker.clickCount());
        assertEquals(1, tracker.clicks("java", 2));
        // Query normalization is case-insensitive.
        assertEquals(1, tracker.clicks("java", 3));
        assertEquals(1.0, tracker.ctr("java", 2), 0.001);
        assertEquals(1.0, tracker.ctr("java", 3), 0.001);
        assertEquals(0.0, tracker.ctr("java", 1), 0.001);
    }

    @Test
    void testBuildPreferencesFromClicks() {
        ClickTracker tracker = new ClickTracker();
        tracker.recordImpression("java", List.of(1, 2, 3));

        // Click on position 3: implies 3 > 1 and 3 > 2.
        tracker.recordClick(new ClickEvent("java", 3, "u3", 3));
        // Click on position 2: implies 2 > 1.
        tracker.recordClick(new ClickEvent("java", 2, "u2", 2));

        List<ClickPreference> preferences = tracker.buildPreferences();
        assertEquals(3, preferences.size());
        assertTrue(preferences.contains(new ClickPreference("java", 3, 1)));
        assertTrue(preferences.contains(new ClickPreference("java", 3, 2)));
        assertTrue(preferences.contains(new ClickPreference("java", 2, 1)));
    }

    @Test
    void testClickOnTopResultProducesNoPreference() {
        ClickTracker tracker = new ClickTracker();
        tracker.recordImpression("java", List.of(1, 2));
        tracker.recordClick(new ClickEvent("java", 1, "u1", 1));
        assertTrue(tracker.buildPreferences().isEmpty());
    }

    @Test
    void testClickWithoutImpressionProducesNoPreference() {
        ClickTracker tracker = new ClickTracker();
        tracker.recordClick(new ClickEvent("unknown", 5, "u5", 5));
        assertTrue(tracker.buildPreferences().isEmpty());
        // CTR is still tracked even without a matching impression.
        assertEquals(1, tracker.clickCount());
    }

    @Test
    void testAverageClickPositionAndTopClicked() {
        ClickTracker tracker = new ClickTracker();
        tracker.recordImpression("java", List.of(1, 2, 3));
        tracker.recordClick(new ClickEvent("java", 2, "u2", 2));
        tracker.recordClick(new ClickEvent("java", 3, "u3", 3));
        tracker.recordClick(new ClickEvent("java", 3, "u3", 3));

        assertEquals(2.6667, tracker.averageClickPosition("java"), 0.001);
        assertEquals(0.0, tracker.averageClickPosition("other"), 0.001);

        List<Map.Entry<Integer, Long>> top = tracker.getTopClicked(2);
        assertEquals(3, top.get(0).getKey(), "Doc 3 has the most clicks");
        assertEquals(2L, top.get(0).getValue());
        assertEquals(2, top.get(1).getKey());
    }

    @Test
    void testImpressionsForQueryReturnsCopy() {
        ClickTracker tracker = new ClickTracker();
        tracker.recordImpression("java", List.of(1, 2));
        List<Integer> copy = tracker.impressionsForQuery("java");
        assertEquals(List.of(1, 2), copy);
        copy.add(99);
        assertEquals(List.of(1, 2), tracker.impressionsForQuery("java"));
    }

    @Test
    void testClear() {
        ClickTracker tracker = new ClickTracker();
        tracker.recordImpression("java", List.of(1, 2));
        tracker.recordClick(new ClickEvent("java", 2, "u2", 2));
        tracker.clear();
        assertEquals(0, tracker.clickCount());
        assertEquals(0, tracker.impressionCount());
        assertTrue(tracker.buildPreferences().isEmpty());
    }
}
