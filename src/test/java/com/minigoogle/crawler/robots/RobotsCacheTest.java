package com.minigoogle.crawler.robots;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

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
}
