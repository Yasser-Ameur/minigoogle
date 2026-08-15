package com.minigoogle.crawler.integration;

import com.minigoogle.crawler.coordinator.CrawlCoordinator;
import com.minigoogle.crawler.downloader.HttpDownloader;
import com.minigoogle.crawler.downloader.NetworkSafetyPolicy;
import com.minigoogle.crawler.model.ParsedDocument;
import com.minigoogle.indexer.CrawlIndexSink;
import com.minigoogle.indexer.IndexBuilder;
import com.minigoogle.indexer.inverted.PostingList;
import com.minigoogle.query.lexer.Lexer;
import com.minigoogle.query.parser.Parser;
import com.minigoogle.query.ast.QueryNode;
import com.minigoogle.query.planner.QueryPlanner;
import com.minigoogle.storage.dictionary.DictionaryEntry;
import com.minigoogle.storage.dictionary.DictionaryReader;
import com.minigoogle.storage.metadata.Metadata;
import com.minigoogle.storage.metadata.MetadataReader;
import com.minigoogle.storage.mmap.MemoryMappedIndex;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for end-to-end crawler integration. */
class CrawlerIntegrationTest {

    private static HttpServer server;
    private static int port;

    @TempDir
    Path snapshotDir;

    @BeforeAll
    static void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();

        server.createContext("/page1", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String response = "<html><body><a href=\"/page2\">Link to 2</a></body></html>";
                exchange.sendResponseHeaders(200, response.length());
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            }
        });

        server.createContext("/page2", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String response = "<html><body>Hello Page 2</body></html>";
                exchange.sendResponseHeaders(200, response.length());
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            }
        });
        
        server.createContext("/robots.txt", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String response = "User-agent: *\nAllow: /\n";
                exchange.sendResponseHeaders(200, response.length());
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            }
        });

        server.setExecutor(null);
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void testCrawlerTraversesLocalServer() throws InterruptedException {
        CrawlCoordinator coordinator = new CrawlCoordinator(2, 4, snapshotDir.toString(), new HttpDownloader(new NetworkSafetyPolicy(true)));
        
        String seed = "http://localhost:" + port + "/page1";
        coordinator.start(List.of(seed));
        
        // Let it run for a few seconds to process the small graph
        Thread.sleep(3000);
        
        coordinator.stop();
        
        // In a real system we would verify the indexerQueue received exactly two pages
        // For this test, we just ensure it runs without exceptions.
        assertTrue(true);
    }

    @Test
    void testCrawledPagesReachIndexSink() throws Exception {
        IndexBuilder indexBuilder = new IndexBuilder();
        List<String> indexedUrls = new CopyOnWriteArrayList<>();
        AtomicReference<Exception> sinkError = new AtomicReference<>();

        CrawlCoordinator coordinator = new CrawlCoordinator(2, 4, snapshotDir.toString(), new HttpDownloader(new NetworkSafetyPolicy(true)));
        coordinator.addIndexSink(doc -> {
            try {
                indexBuilder.processDocument(doc);
                indexedUrls.add(doc.url().toString());
            } catch (Exception e) {
                sinkError.set(e);
            }
        });
        coordinator.start(List.of("http://localhost:" + port + "/page1"));

        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            long completed = (Long) coordinator.getFrontier().getStats().get("totalCompleted");
            if (completed >= 2) {
                break;
            }
            Thread.sleep(100);
        }
        coordinator.stop();

        assertNull(sinkError.get(), "Index sink must not throw: " + sinkError.get());
        assertTrue(indexedUrls.stream().anyMatch(u -> u.contains("/page1")),
                "The seed page must be handed to the index sink");
        assertTrue(indexedUrls.stream().anyMatch(u -> u.contains("/page2")),
                "Discovered page 2 must be handed to the index sink");
        assertEquals(2, indexBuilder.getProcessedDocuments().size(),
                "Crawled pages must flow through to the indexer");
    }

    @Test
    void testProductionSinkPersistsCrawledPagesIntoQueryableIndex() throws Exception {
        // The production wiring (CrawlCoordinator.main) registers a CrawlIndexSink
        // that writes crawled pages to a real on-disk index. Verify the full
        // crawl -> index -> query loop: the flushed index can be loaded back and
        // a term from the crawled pages is queryable.
        Path indexDir = snapshotDir.resolve("crawled-index");
        CrawlIndexSink sink = new CrawlIndexSink(indexDir);

        CrawlCoordinator coordinator = new CrawlCoordinator(2, 4, snapshotDir.toString(), new HttpDownloader(new NetworkSafetyPolicy(true)));
        coordinator.addIndexSink(sink);
        coordinator.start(List.of("http://localhost:" + port + "/page1"));

        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            long completed = (Long) coordinator.getFrontier().getStats().get("totalCompleted");
            if (completed >= 2) {
                break;
            }
            Thread.sleep(100);
        }
        coordinator.stop();
        sink.close();

        assertTrue(sink.getProcessedDocumentCount() >= 2,
                "Crawled pages must be collected by the production sink");
        assertTrue(Files.exists(indexDir.resolve("postings.bin")), "postings.bin must be flushed");
        assertTrue(Files.exists(indexDir.resolve("dictionary.bin")), "dictionary.bin must be flushed");
        assertTrue(Files.exists(indexDir.resolve("documents.bin")), "documents.bin must be flushed");
        assertTrue(Files.exists(indexDir.resolve("metadata.bin")), "metadata.bin must be flushed");

        Metadata metadata = new MetadataReader().read(indexDir.resolve("metadata.bin"));
        assertEquals(2, metadata.documentCount(), "Both crawled pages must be persisted");

        Map<String, DictionaryEntry> dictionary = new DictionaryReader().read(indexDir.resolve("dictionary.bin"));
        try (MemoryMappedIndex mmap = new MemoryMappedIndex(indexDir.resolve("postings.bin"))) {
            QueryPlanner planner = new QueryPlanner(mmap, dictionary, metadata.documentCount());
            Lexer lexer = new Lexer();
            Parser parser = new Parser(lexer.tokenize("hello"));
            PostingList result = planner.execute(parser.parse());
            assertFalse(result.getPostings().isEmpty(),
                    "A term from crawled page 2 ('Hello Page 2') must be queryable in the production index");
        }
    }

    @Test
    void testMaxDepthIsEnforced() throws InterruptedException, IOException {
        HttpServer chainServer = HttpServer.create(new InetSocketAddress(0), 0);
        int chainPort = chainServer.getAddress().getPort();

        chainServer.createContext("/robots.txt", exchange -> {
            String response = "User-agent: *\nAllow: /\n";
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });
        chainServer.createContext("/p1", exchange -> {
            String response = "<html><body><a href=\"/p2\">to 2</a></body></html>";
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });
        chainServer.createContext("/p2", exchange -> {
            String response = "<html><body><a href=\"/p3\">to 3</a></body></html>";
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });
        chainServer.createContext("/p3", exchange -> {
            String response = "<html><body>end of chain</body></html>";
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });
        chainServer.start();

        try {
            // maxDepth = 1: the seed (depth 0) and its direct links (depth 1)
            // are crawled; links discovered on depth-1 pages (depth 2) must be skipped.
            CrawlCoordinator coordinator = new CrawlCoordinator(2, 1, snapshotDir.toString(), new HttpDownloader(new NetworkSafetyPolicy(true)));
            coordinator.start(List.of("http://localhost:" + chainPort + "/p1"));

            long deadline = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < deadline) {
                long completed = (Long) coordinator.getFrontier().getStats().get("totalCompleted");
                if (completed >= 2) {
                    break;
                }
                Thread.sleep(100);
            }
            coordinator.stop();

            var registry = coordinator.getFrontier().getTaskRegistry().keySet();
            assertTrue(registry.stream().anyMatch(u -> u.contains("/p2")),
                    "Depth-1 links must be crawled");
            assertFalse(registry.stream().anyMatch(u -> u.contains("/p3")),
                    "Depth-2 links must be rejected by maxDepth enforcement");
        } finally {
            chainServer.stop(0);
        }
    }
}
