package com.minigoogle.crawler.robots;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Robots.txt cache with TTL expiration, persistence, and concurrent access.
 * Wraps {@link RobotsManager} to avoid redundant fetches by caching per-domain
 * allow/deny results in a thread-safe {@link ConcurrentHashMap}.
 */
public class RobotsCache {

    private static final Logger logger = LoggerFactory.getLogger(RobotsCache.class);
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
    private static final int CACHE_VERSION = 1;

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

    private RobotsCache(ConcurrentHashMap<String, CacheEntry> cache) {
        this.robotsManager = null;
        this.cache = cache;
        this.ttl = DEFAULT_TTL;
    }

    public boolean isAllowed(URI uri) {
        if (uri == null || uri.getHost() == null) return false;
        String host = uri.getHost().toLowerCase();

        CacheEntry entry = cache.get(host);
        if (entry != null && !entry.isExpired()) {
            return entry.isAllowed(uri.getPath());
        }

        boolean allowed = robotsManager.isAllowed(uri);
        cache.compute(host, (k, existing) -> {
            if (existing == null || existing.isExpired()) {
                return new CacheEntry(allowed, uri.getPath());
            }
            return existing;
        });

        logger.debug("Fetched robots.txt for {}: allowed={}", host, allowed);
        return allowed;
    }

    public long getCrawlDelayMillis(URI uri) {
        return robotsManager.getCrawlDelayMillis(uri);
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
                throw new IOException("Incompatible robots cache version: " + version);
            }
            int count = dis.readInt();
            for (int i = 0; i < count; i++) {
                String domain = dis.readUTF();
                CacheEntry entry = CacheEntry.readFrom(dis);
                loaded.put(domain, entry);
            }
        }
        logger.debug("Robots cache loaded ({} entries) from {}", loaded.size(), filePath);
        return new RobotsCache(loaded);
    }

    public static class CacheEntry implements Serializable {
        private final Instant fetchedAt;
        private final boolean defaultAllowed;
        private final ConcurrentHashMap<String, Boolean> pathRules;

        CacheEntry(boolean defaultAllowed, String path) {
            this.fetchedAt = Instant.now();
            this.defaultAllowed = defaultAllowed;
            this.pathRules = new ConcurrentHashMap<>();
            if (path != null) {
                pathRules.put(path, defaultAllowed);
            }
        }

        CacheEntry(Instant fetchedAt, boolean defaultAllowed, ConcurrentHashMap<String, Boolean> pathRules) {
            this.fetchedAt = fetchedAt;
            this.defaultAllowed = defaultAllowed;
            this.pathRules = pathRules;
        }

        boolean isExpired() {
            return Instant.now().isAfter(fetchedAt.plus(DEFAULT_TTL));
        }

        boolean isAllowed(String path) {
            if (path == null || path.isEmpty()) path = "/";
            return pathRules.getOrDefault(path, defaultAllowed);
        }

        public Instant getFetchedAt() { return fetchedAt; }
        public boolean isDefaultAllowed() { return defaultAllowed; }
        public Map<String, Boolean> getPathRules() { return pathRules; }

        void writeTo(DataOutputStream dos) throws IOException {
            dos.writeLong(fetchedAt.toEpochMilli());
            dos.writeBoolean(defaultAllowed);
            dos.writeInt(pathRules.size());
            for (Map.Entry<String, Boolean> rule : pathRules.entrySet()) {
                dos.writeUTF(rule.getKey());
                dos.writeBoolean(rule.getValue());
            }
        }

        static CacheEntry readFrom(DataInputStream dis) throws IOException {
            Instant fetchedAt = Instant.ofEpochMilli(dis.readLong());
            boolean defaultAllowed = dis.readBoolean();
            int ruleCount = dis.readInt();
            ConcurrentHashMap<String, Boolean> pathRules = new ConcurrentHashMap<>();
            for (int j = 0; j < ruleCount; j++) {
                String path = dis.readUTF();
                boolean allowed = dis.readBoolean();
                pathRules.put(path, allowed);
            }
            return new CacheEntry(fetchedAt, defaultAllowed, pathRules);
        }
    }
}
