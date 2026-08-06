package com.minigoogle.crawler.robots;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Robots.txt cache with TTL expiration, persistence, and concurrent access.
 * Wraps {@link RobotsManager} to avoid redundant fetches by caching the full
 * per-origin rule set (path rules and crawl delay) in a thread-safe
 * {@link ConcurrentHashMap}, keyed by host.
 */
public class RobotsCache {

    private static final Logger logger = LoggerFactory.getLogger(RobotsCache.class);
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
    private static final int CACHE_VERSION = 2;

    private final RobotsManager robotsManager;
    private final ConcurrentHashMap<String, CacheEntry> cache;
    private final Duration ttl;

    public RobotsCache(RobotsManager robotsManager) {
        this(robotsManager, DEFAULT_TTL);
    }

    public RobotsCache(RobotsManager robotsManager, Duration ttl) {
        this.robotsManager = robotsManager;
        this.cache = new ConcurrentHashMap<>();
        this.ttl = ttl;
    }

    public boolean isAllowed(URI uri) {
        if (uri == null || uri.getHost() == null) return false;
        String host = uri.getHost().toLowerCase();
        String path = uri.getPath();

        CacheEntry entry = cache.get(host);
        if (entry != null && !entry.isExpired(ttl)) {
            return entry.isAllowed(path);
        }

        RobotsManager.Rules rules = robotsManager.fetchAndParseRobots(uri);
        CacheEntry fresh = new CacheEntry(Instant.now(), rules);
        cache.compute(host, (k, existing) ->
            (existing != null && !existing.isExpired(ttl)) ? existing : fresh);

        logger.debug("Fetched robots.txt for {}: defaultAllowed={}", host, rules.defaultAllowed());
        return fresh.isAllowed(path);
    }

    public long getCrawlDelayMillis(URI uri) {
        if (uri == null || uri.getHost() == null) return 0;
        String host = uri.getHost().toLowerCase();

        CacheEntry entry = cache.get(host);
        if (entry == null || entry.isExpired(ttl)) {
            RobotsManager.Rules rules = robotsManager.fetchAndParseRobots(uri);
            CacheEntry fresh = new CacheEntry(Instant.now(), rules);
            cache.compute(host, (k, existing) ->
                (existing != null && !existing.isExpired(ttl)) ? existing : fresh);
            entry = fresh;
        }
        return entry.getCrawlDelayMillis();
    }

    public void invalidate(String host) {
        cache.remove(host.toLowerCase());
        logger.debug("Invalidated robots cache for {}", host);
    }

    public void clear() {
        cache.clear();
    }

    public int cachedDomainCount() {
        return cache.size();
    }

    public Map<String, CacheEntry> getCacheEntries() {
        return cache;
    }

    public void save(String filePath) throws IOException {
        Path path = Path.of(filePath);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(filePath)))) {
            dos.writeInt(CACHE_VERSION);
            dos.writeInt(cache.size());
            for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
                dos.writeUTF(entry.getKey());
                entry.getValue().writeTo(dos);
            }
        }
        logger.debug("Robots cache saved ({} entries) to {}", cache.size(), filePath);
    }

    public static RobotsCache load(String filePath) throws IOException {
        ConcurrentHashMap<String, CacheEntry> loaded = new ConcurrentHashMap<>();
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(filePath)))) {
            int version = dis.readInt();
            if (version != CACHE_VERSION) {
                logger.warn("Incompatible robots cache version: {}, expected {}. Starting empty.", version, CACHE_VERSION);
                return new RobotsCache(new RobotsManager());
            }
            int count = dis.readInt();
            for (int i = 0; i < count; i++) {
                String domain = dis.readUTF();
                CacheEntry entry = CacheEntry.readFrom(dis);
                loaded.put(domain, entry);
            }
        }
        RobotsCache cache = new RobotsCache(new RobotsManager());
        cache.cache.putAll(loaded);
        logger.debug("Robots cache loaded ({} entries) from {}", loaded.size(), filePath);
        return cache;
    }

    public static class CacheEntry implements Serializable {
        private final Instant fetchedAt;
        private final RobotsManager.Rules rules;

        CacheEntry(Instant fetchedAt, RobotsManager.Rules rules) {
            this.fetchedAt = fetchedAt;
            this.rules = rules;
        }

        boolean isExpired(Duration ttl) {
            return Instant.now().isAfter(fetchedAt.plus(ttl));
        }

        boolean isAllowed(String path) {
            return rules.isAllowed(path);
        }

        long getCrawlDelayMillis() {
            return rules.crawlDelaySeconds() * 1000L;
        }

        public Instant getFetchedAt() { return fetchedAt; }
        public RobotsManager.Rules getRules() { return rules; }

        void writeTo(DataOutputStream dos) throws IOException {
            dos.writeLong(fetchedAt.toEpochMilli());
            Map<String, Boolean> pathRules = rules.pathRules();
            dos.writeInt(pathRules.size());
            for (Map.Entry<String, Boolean> rule : pathRules.entrySet()) {
                dos.writeUTF(rule.getKey());
                dos.writeBoolean(rule.getValue());
            }
            dos.writeLong(rules.crawlDelaySeconds());
            dos.writeBoolean(rules.defaultAllowed());
        }

        static CacheEntry readFrom(DataInputStream dis) throws IOException {
            Instant fetchedAt = Instant.ofEpochMilli(dis.readLong());
            int ruleCount = dis.readInt();
            Map<String, Boolean> pathRules = new HashMap<>();
            for (int j = 0; j < ruleCount; j++) {
                pathRules.put(dis.readUTF(), dis.readBoolean());
            }
            long crawlDelay = dis.readLong();
            boolean defaultAllowed = dis.readBoolean();
            RobotsManager.Rules rules = new RobotsManager.Rules(pathRules, crawlDelay, defaultAllowed);
            return new CacheEntry(fetchedAt, rules);
        }
    }
}
