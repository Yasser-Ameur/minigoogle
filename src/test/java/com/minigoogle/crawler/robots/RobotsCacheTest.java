package com.minigoogle.crawler.robots;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for RobotsCache functionality. */
class RobotsCacheTest {

    @TempDir
    Path tempDir;

    @Test
    void testSaveAndLoad() throws IOException {
        RobotsCache original = new RobotsCache(new RobotsManager());
        String savePath = tempDir.resolve("robots_cache.dat").toString();

        original.save(savePath);

        RobotsCache loaded = RobotsCache.load(savePath);
        assertNotNull(loaded);
        assertEquals(0, loaded.cachedDomainCount());
    }

    @Test
    void testSaveCreatesParentDirectories() throws IOException {
        Path nested = tempDir.resolve("deep").resolve("nested");
        RobotsCache cache = new RobotsCache(new RobotsManager());
        String savePath = nested.resolve("robots.dat").toString();

        cache.save(savePath);
        assertTrue(nested.toFile().exists());
    }

    @Test
    void testLoadNonExistentFileThrows() {
        assertThrows(IOException.class, () -> {
            RobotsCache.load(tempDir.resolve("nonexistent.dat").toString());
        });
    }

    @Test
    void testPathLevelRulesRespectedViaCache() {
        RobotsCache cache = new RobotsCache(new RobotsManager());

        Map<String, Boolean> rules = new HashMap<>();
        rules.put("/private", false);
        RobotsManager.Rules parsed = new RobotsManager.Rules(rules, 2, true);
        cache.getCacheEntries().put("example.com", new RobotsCache.CacheEntry(Instant.now(), parsed));

        assertFalse(cache.isAllowed(URI.create("https://example.com/private/x")));
        assertTrue(cache.isAllowed(URI.create("https://example.com/public/y")));
        assertEquals(2000, cache.getCrawlDelayMillis(URI.create("https://example.com/page")));
    }

    @Test
    void testDefaultDisallowedRules() {
        RobotsCache cache = new RobotsCache(new RobotsManager());

        RobotsManager.Rules parsed = new RobotsManager.Rules(new HashMap<>(), 0, false);
        cache.getCacheEntries().put("example.com", new RobotsCache.CacheEntry(Instant.now(), parsed));

        assertFalse(cache.isAllowed(URI.create("https://example.com/anything")));
    }

    @Test
    void testSaveAndLoadPreservesRules() throws IOException {
        RobotsCache original = new RobotsCache(new RobotsManager());

        Map<String, Boolean> rules = new HashMap<>();
        rules.put("/private", false);
        RobotsManager.Rules parsed = new RobotsManager.Rules(rules, 3, true);
        original.getCacheEntries().put("example.com", new RobotsCache.CacheEntry(Instant.now(), parsed));

        String savePath = tempDir.resolve("robots_rules.dat").toString();
        original.save(savePath);

        RobotsCache loaded = RobotsCache.load(savePath);
        assertEquals(1, loaded.cachedDomainCount());
        assertFalse(loaded.isAllowed(URI.create("https://example.com/private/x")));
        assertTrue(loaded.isAllowed(URI.create("https://example.com/public")));
        assertEquals(3000, loaded.getCrawlDelayMillis(URI.create("https://example.com/")));
    }
}
