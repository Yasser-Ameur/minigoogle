package com.minigoogle.ml.eval;

import com.minigoogle.crawler.model.ParsedDocument;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Deterministic synthetic corpus with graded relevance judgments for the
 * offline quality harness.
 *
 * <p>Documents are generated around a small set of topics, each with a keyword
 * pool. A document's text repeats its topic keywords in proportion to its
 * relevance grade (4 = perfect, 1 = marginal), so lexical BM25 is a noisy but
 * usable relevance signal. Noise keywords from unrelated topics create
 * distractors that share vocabulary with queries they are not relevant to —
 * this is what makes precision a meaningful measurement. Higher-grade
 * documents receive more inbound links, giving PageRank a learnable
 * correlation with relevance.</p>
 *
 * <p>Relevance is fully known by construction: for a query targeting a topic,
 * every document whose primary topic is that topic carries its grade, and
 * everything else is graded 0. This makes the harness a closed, reproducible
 * experiment: same seed, same corpus, same judgments, same metrics.</p>
 */
public final class SyntheticCorpus {

    private static final String[] FILLER = {
        "the", "data", "system", "result", "process", "method", "value", "context",
        "analysis", "overview", "study", "report", "detail", "section", "chapter",
        "approach", "framework", "component", "layer", "model", "interface"
    };

    public record Topic(String name, List<String> keywords) {
    }

    public record JudgedQuery(String query, Map<String, Integer> urlToGrade) {
    }

    public record JudgedCorpus(
            List<ParsedDocument> docs,
            List<JudgedQuery> queries,
            Map<String, Integer> urlToDocId) {
    }

    private SyntheticCorpus() {
    }

    public static JudgedCorpus generate(long seed) {
        return generate(seed, 8, 40);
    }

    public static JudgedCorpus generate(long seed, int topicCount, int docsPerTopic) {
        Random rnd = new Random(seed);
        List<Topic> topics = defaultTopics(topicCount);

        // Pass 1: build every document body and record its primary topic + grade.
        Map<Integer, String> docTopic = new HashMap<>();
        Map<Integer, Integer> docGrade = new HashMap<>();
        Map<String, Integer> urlToDocId = new HashMap<>();
        List<String> bodies = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        int docIdCounter = 1;
        for (Topic topic : topics) {
            for (int i = 0; i < docsPerTopic; i++) {
                int docId = docIdCounter++;
                int grade = pickGrade(rnd);
                String url = "http://synthetic.example.com/" + topic.name() + "/" + docId;
                String title = grade >= 3
                        ? titleCase(topic.name()) + " " + words(2, rnd)
                        : words(3, rnd);
                String text = buildText(topic, grade, topics, rnd);
                docTopic.put(docId, topic.name());
                docGrade.put(docId, grade);
                urlToDocId.put(url, docId);
                bodies.add(text);
                titles.add(title);
                urls.add(url);
            }
        }

        // Pass 2: link graph. Each document links forward; inbound-link count is
        // weighted by grade, so PageRank correlates with relevance.
        List<List<URI>> links = new ArrayList<>();
        for (int docId = 1; docId <= urlToDocId.size(); docId++) {
            List<URI> out = new ArrayList<>();
            String base = "http://synthetic.example.com/";
            int targetCount = 3 + rnd.nextInt(3);
            for (int t = 0; t < targetCount; t++) {
                int candidate = 1 + rnd.nextInt(urlToDocId.size());
                int grade = docGrade.get(candidate);
                // Re-roll low-grade targets: high-grade documents are linked more.
                if (rnd.nextDouble() > 0.5 + 0.2 * grade) {
                    continue;
                }
                out.add(URI.create(base + docTopic.get(candidate) + "/" + candidate));
            }
            links.add(out);
        }

        List<ParsedDocument> docs = new ArrayList<>(urlToDocId.size());
        for (int docId = 1; docId <= urlToDocId.size(); docId++) {
            docs.add(new ParsedDocument(UUID.randomUUID(), URI.create(urls.get(docId - 1)),
                    titles.get(docId - 1), bodies.get(docId - 1), links.get(docId - 1),
                    Instant.parse("2026-01-01T00:00:00Z")));
        }

        List<JudgedQuery> queries = new ArrayList<>();
        for (Topic topic : topics) {
            Map<String, Integer> grades = new HashMap<>();
            for (int docId = 1; docId <= urlToDocId.size(); docId++) {
                if (topic.name().equals(docTopic.get(docId))) {
                    grades.put(urls.get(docId - 1), docGrade.get(docId));
                }
            }
            List<String> q1 = pickKeywords(topic, 3, rnd);
            List<String> q2 = pickKeywords(topic, 2, rnd);
            queries.add(new JudgedQuery(String.join(" ", q1), grades));
            queries.add(new JudgedQuery(String.join(" ", q2), grades));
        }
        // Shuffle query order deterministically so evaluation order is stable.
        Collections.shuffle(queries, rnd);

        return new JudgedCorpus(docs, queries, urlToDocId);
    }

    private static List<Topic> defaultTopics(int count) {
        String[][] pools = {
            {"consensus", "replication", "partition", "quorum", "raft", "vector-clock", "sharding", "failover"},
            {"laser", "photon", "quantum", "superposition", "qubit", "entanglement", "interference", "photonics"},
            {"saxophone", "bebop", "swing", "improvisation", "trumpet", "big-band", "modal", "scat"},
            {"solar", "turbine", "photovoltaic", "geothermal", "windfarm", "battery", "renewable", "grid"},
            {"gradient", "backpropagation", "neural", "overfitting", "regularization", "embedding", "attention", "inference"},
            {"canvas", "fresco", "sculpture", "renaissance", "pigment", "gallery", "portrait", "atelier"},
            {"trench", "abyssal", "bioluminescence", "coral", "zooplankton", "hydrothermal", "oceanic", "benthic"},
            {"marathon", "sprint", "gymnastics", "podium", "medal", "record", "training", "sportsmanship"},
            {"cryptography", "signature", "hash", "audit", "secret", "encryption", "nonce", "threshold"},
            {"orchestra", "symphony", "tempo", "concerto", "strings", "conductor", "violin", "rhythm"}
        };
        List<Topic> topics = new ArrayList<>();
        for (int i = 0; i < Math.min(count, pools.length); i++) {
            topics.add(new Topic("topic-" + (i + 1), List.of(pools[i])));
        }
        return topics;
    }

    private static int pickGrade(Random rnd) {
        double r = rnd.nextDouble();
        if (r < 0.25) return 4;
        if (r < 0.55) return 3;
        if (r < 0.80) return 2;
        return 1;
    }

    private static String buildText(Topic topic, int grade, List<Topic> allTopics, Random rnd) {
        List<String> words = new ArrayList<>();
        int mentions = grade * 3;
        for (int i = 0; i < mentions; i++) {
            words.add(topic.keywords().get(rnd.nextInt(topic.keywords().size())));
        }
        int fillerCount = 12 + rnd.nextInt(8);
        for (int i = 0; i < fillerCount; i++) {
            words.add(FILLER[rnd.nextInt(FILLER.length)]);
        }
        int noise = 2;
        for (int i = 0; i < noise; i++) {
            Topic other = allTopics.get(rnd.nextInt(allTopics.size()));
            if (other != topic) {
                words.add(other.keywords().get(rnd.nextInt(other.keywords().size())));
            }
        }
        Collections.shuffle(words, rnd);
        return String.join(" ", words);
    }

    private static List<String> pickKeywords(Topic topic, int count, Random rnd) {
        List<String> copy = new ArrayList<>(topic.keywords());
        Collections.shuffle(copy, rnd);
        return copy.subList(0, Math.min(count, copy.size()));
    }

    private static String words(int count, Random rnd) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(' ');
            sb.append(FILLER[rnd.nextInt(FILLER.length)]);
        }
        return sb.toString();
    }

    private static String titleCase(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (String part : s.split("-")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
