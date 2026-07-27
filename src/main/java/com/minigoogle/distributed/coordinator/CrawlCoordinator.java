package com.minigoogle.distributed.coordinator;

import com.google.gson.Gson;
import com.minigoogle.crawler.frontier.FrontierQueue;
import com.minigoogle.crawler.model.UrlTask;
import com.minigoogle.network.http.RestServer;

import java.net.URI;
import java.time.Instant;

/**
 * Distributes URLs from a central FrontierQueue to CrawlWorkers.
 */
public class CrawlCoordinator {

    private final FrontierQueue frontier;
    private final RestServer server;
    private final Gson gson;

    public CrawlCoordinator(FrontierQueue frontier, int port) {
        this.frontier = frontier;
        this.server = new RestServer(port);
        this.gson = new Gson();
        setupRoutes();
    }

    private void setupRoutes() {
        // Workers request a new URL to crawl
        server.get("/task", body -> {
            try {
                UrlTask nextTask = frontier.take();
                if (nextTask != null) {
                    return gson.toJson(new CrawlTask(nextTask.normalizedUrl(), nextTask.depth()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "{}"; // Empty response indicates no work
        });

        // Workers submit new links they discovered
        server.post("/links", body -> {
            DiscoveredLinks req = gson.fromJson(body, DiscoveredLinks.class);
            if (req != null && req.links != null && req.depth != null) {
                for (String link : req.links) {
                    String domain = extractDomain(link);
                    frontier.add(new UrlTask(link, domain, req.depth + 1, Instant.now()));
                }
            }
            return "{\"status\":\"OK\"}";
        });
    }

    private String extractDomain(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            return host != null ? host : url;
        } catch (Exception e) {
            return url;
        }
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop();
    }

    // DTOs
    private static class CrawlTask {
        String url;
        int depth;

        public CrawlTask(String url, int depth) {
            this.url = url;
            this.depth = depth;
        }
    }

    private static class DiscoveredLinks {
        java.util.List<String> links;
        Integer depth;
    }
}
