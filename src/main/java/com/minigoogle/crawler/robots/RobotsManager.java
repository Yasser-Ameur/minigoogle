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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Robots.txt parser and fetcher with crawl-delay rate-limit enforcement.
 * Downloads and parses robots.txt for each origin, providing path-level
 * allow/disallow decisions with longest-prefix matching and per-origin
 * crawl-delay discovery. Fetches honor the request's scheme and port and
 * fall back from HTTPS to HTTP when the primary scheme is unreachable.
 */
public class RobotsManager {

    private static final Logger logger = LoggerFactory.getLogger(RobotsManager.class);
    private static final String USER_AGENT = "MiniGoogleBot/1.0";
    private static final String BOT_NAME = "MiniGoogleBot";

    private final HttpClient client;

    public RobotsManager() {
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    public boolean isAllowed(URI uri) {
        if (uri == null || uri.getHost() == null) return false;
        return fetchAndParseRobots(uri).isAllowed(pathOf(uri));
    }

    public long getCrawlDelayMillis(URI uri) {
        if (uri == null || uri.getHost() == null) return 0;
        return fetchAndParseRobots(uri).crawlDelaySeconds() * 1000L;
    }

    /**
     * Fetches and parses robots.txt for the origin of {@code uri}.
     * Tries the request's scheme first, then HTTPS and HTTP as fallbacks.
     * Status handling: 200 parses rules; 401/403 disallow the whole site;
     * 404/410 means no rules exist (allow everything); other statuses default
     * to allowing. Any network failure also defaults to allowing so crawling
     * is never hard-blocked by an unreachable robots endpoint.
     */
    public Rules fetchAndParseRobots(URI uri) {
        if (uri == null || uri.getHost() == null) return Rules.DEFAULT;
        String host = uri.getHost().toLowerCase();
        int port = uri.getPort();

        Set<String> schemes = new LinkedHashSet<>();
        schemes.add(uri.getScheme() != null ? uri.getScheme() : "https");
        schemes.add("https");
        schemes.add("http");

        for (String scheme : schemes) {
            try {
                URI robotsUri = new URI(scheme, null, host, port >= 0 ? port : -1, "/robots.txt", null, null);
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(robotsUri)
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                if (status == 200) {
                    return parseRobotsTxt(response.body());
                }
                if (status == 401 || status == 403) {
                    logger.warn("robots.txt for {} returned {}, disallowing entire site", robotsUri, status);
                    return Rules.DISALLOW_ALL;
                }
                if (status == 404 || status == 410) {
                    logger.debug("robots.txt for {} not found ({}), assuming no restrictions", robotsUri, status);
                    return Rules.DEFAULT;
                }
                logger.debug("robots.txt for {} returned {}, assuming no restrictions", robotsUri, status);
                return Rules.DEFAULT;
            } catch (Exception e) {
                logger.debug("Could not fetch robots.txt via {}://{}:{}, trying next scheme. Error: {}",
                    scheme, host, port, e.getMessage());
            }
        }
        return Rules.DEFAULT;
    }

    private static String pathOf(URI uri) {
        String path = uri.getPath();
        return (path == null || path.isEmpty()) ? "/" : path;
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
                        crawlDelay = (long) Double.parseDouble(value);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        return new Rules(pathsAllowed, crawlDelay, true);
    }

    /**
     * Parsed robots.txt rules for a single origin with longest-prefix matching.
     * A path matches the most specific rule; if no rule matches, the decision
     * falls back to {@code defaultAllowed}.
     */
    public record Rules(Map<String, Boolean> pathRules, long crawlDelaySeconds, boolean defaultAllowed) {
        static final Rules DEFAULT = new Rules(Collections.emptyMap(), 0, true);
        static final Rules DISALLOW_ALL = new Rules(Collections.emptyMap(), 0, false);

        boolean isAllowed(String path) {
            if (path == null || path.isEmpty()) path = "/";

            String longestMatch = "";
            Boolean allowed = null;

            for (Map.Entry<String, Boolean> entry : pathRules.entrySet()) {
                String rulePath = entry.getKey();
                if (path.startsWith(rulePath) && rulePath.length() > longestMatch.length()) {
                    longestMatch = rulePath;
                    allowed = entry.getValue();
                }
            }
            return allowed != null ? allowed : defaultAllowed;
        }
    }
}
