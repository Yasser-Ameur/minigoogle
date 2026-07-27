package com.minigoogle.crawler.robots;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Robots.txt parser and fetcher with crawl-delay rate-limit enforcement.
 * Downloads and parses robots.txt for each domain, caches the resulting rules,
 * and provides path-level allow/disallow decisions with longest-prefix matching.
 */
public class RobotsManager {

    private static final Logger logger = LoggerFactory.getLogger(RobotsManager.class);
    private static final String USER_AGENT = "MiniGoogleBot/1.0";
    private static final String BOT_NAME = "MiniGoogleBot";

    private final HttpClient client;
    private final ConcurrentHashMap<String, Rules> domainRules = new ConcurrentHashMap<>();

    public RobotsManager() {
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    public boolean isAllowed(URI uri) {
        if (uri == null || uri.getHost() == null) return false;
        String host = uri.getHost().toLowerCase();
        
        Rules rules = domainRules.computeIfAbsent(host, this::fetchAndParseRobots);
        
        String path = uri.getPath();
        if (path == null || path.isEmpty()) path = "/";
        
        return rules.isAllowed(path);
    }
    
    public long getCrawlDelayMillis(URI uri) {
         if (uri == null || uri.getHost() == null) return 0;
         String host = uri.getHost().toLowerCase();
         Rules rules = domainRules.getOrDefault(host, Rules.DEFAULT);
         return rules.crawlDelaySeconds() * 1000L;
    }

    private Rules fetchAndParseRobots(String host) {
        try {
            URI robotsUri = new URI("https", null, host, -1, "/robots.txt", null, null);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(robotsUri)
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                return parseRobotsTxt(response.body());
            }
        } catch (Exception e) {
            logger.warn("Could not fetch robots.txt for {}, assuming allowed. Error: {}", host, e.getMessage());
        }
        return Rules.DEFAULT;
    }

    private Rules parseRobotsTxt(String content) {
        Map<String, Boolean> pathsAllowed = new HashMap<>();
        long crawlDelay = 0;
        
        boolean inUserAgentBlock = false;
        
        String[] lines = content.split("\\r?\\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            
            String[] parts = line.split(":", 2);
            if (parts.length != 2) continue;
            
            String key = parts[0].trim().toLowerCase();
            String value = parts[1].trim();
            
            if (key.equals("user-agent")) {
                inUserAgentBlock = value.equalsIgnoreCase(BOT_NAME) || value.equals("*");
            } else if (inUserAgentBlock) {
                if (key.equals("disallow")) {
                    if (!value.isEmpty()) pathsAllowed.put(value, false);
                } else if (key.equals("allow")) {
                    if (!value.isEmpty()) pathsAllowed.put(value, true);
                } else if (key.equals("crawl-delay")) {
                    try {
                        crawlDelay = Long.parseLong(value);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        
        return new Rules(pathsAllowed, crawlDelay);
    }

    record Rules(Map<String, Boolean> pathRules, long crawlDelaySeconds) {
        static final Rules DEFAULT = new Rules(Collections.emptyMap(), 0);
        
        boolean isAllowed(String path) {
            // Find most specific match
            String longestMatch = "";
            Boolean allowed = true;
            
            for (Map.Entry<String, Boolean> entry : pathRules.entrySet()) {
                String rulePath = entry.getKey();
                if (path.startsWith(rulePath) && rulePath.length() > longestMatch.length()) {
                    longestMatch = rulePath;
                    allowed = entry.getValue();
                }
            }
            return allowed;
        }
    }
}
