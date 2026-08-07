package com.minigoogle.corpus;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads a BEIR {@code qrels/<split>.tsv} relevance-judgment file.
 *
 * <p>Accepts both the TREC format ({@code qid Q0 docid grade}) and the compact
 * BEIR format ({@code qid docid grade}), tab- or whitespace-separated, and
 * tolerates an optional header row. Only judgments with grade &gt; 0 are kept.
 * Grades may be integers or floats (e.g. Touché); floats are rounded.</p>
 */
public final class BeirQrelsReader {

    private BeirQrelsReader() {
    }

    public static Map<String, Map<String, Integer>> read(Path qrelsFile) throws IOException {
        Map<String, Map<String, Integer>> qrels = new LinkedHashMap<>();
        if (!Files.exists(qrelsFile)) {
            return Map.of();
        }
        try (BufferedReader reader = Files.newBufferedReader(qrelsFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] cols = parse(line);
                if (cols.length < 3) {
                    continue;
                }
                String qid = cols[0];
                if (isHeader(qid)) {
                    continue;
                }
                String docId = cols[cols.length - 2];
                int grade = parseGrade(cols[cols.length - 1]);
                if (grade <= 0) {
                    continue;
                }
                qrels.computeIfAbsent(qid, k -> new HashMap<>()).put(docId, grade);
            }
        }
        Map<String, Map<String, Integer>> frozen = new HashMap<>();
        for (Map.Entry<String, Map<String, Integer>> e : qrels.entrySet()) {
            frozen.put(e.getKey(), Map.copyOf(e.getValue()));
        }
        return Map.copyOf(frozen);
    }

    private static String[] parse(String line) {
        String[] tabs = line.split("\t");
        if (tabs.length >= 3) {
            return tabs;
        }
        return line.trim().split("\\s+");
    }

    private static boolean isHeader(String first) {
        return first.equalsIgnoreCase("query-id") || first.equalsIgnoreCase("queryid")
                || first.equalsIgnoreCase("qid") || first.equalsIgnoreCase("topic");
    }

    private static int parseGrade(String token) {
        try {
            return Integer.parseInt(token.trim());
        } catch (NumberFormatException e) {
            try {
                return (int) Math.round(Double.parseDouble(token.trim()));
            } catch (NumberFormatException e2) {
                return 0;
            }
        }
    }
}
