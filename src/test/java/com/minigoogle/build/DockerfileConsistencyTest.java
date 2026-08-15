package com.minigoogle.build;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Keeps the Docker build context self-consistent: every file copied from the
 * build context by the Dockerfile must not be excluded by .dockerignore.
 * A COPY source that is silently ignored breaks the image build, so this test
 * pins the two files in sync.
 */
class DockerfileConsistencyTest {

    private static final Path ROOT = Paths.get(System.getProperty("user.dir"));

    @Test
    void everyContextCopySourceIsNotDockerignored() throws IOException {
        List<String> ignorePatterns = new ArrayList<>();
        for (String line : Files.readAllLines(ROOT.resolve(".dockerignore"))) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            ignorePatterns.add(trimmed);
        }

        List<String> copySources = contextCopySources();

        assertFalse(copySources.isEmpty(), "Dockerfile must contain context COPY statements");
        for (String source : copySources) {
            for (String pattern : ignorePatterns) {
                assertFalse(matches(pattern, source),
                        "Dockerfile COPY source '" + source + "' is excluded by .dockerignore pattern '" + pattern + "'");
            }
        }
    }

    private List<String> contextCopySources() throws IOException {
        List<String> sources = new ArrayList<>();
        for (String line : Files.readAllLines(ROOT.resolve("Dockerfile"))) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("COPY ")) {
                continue;
            }
            if (trimmed.contains("--from=")) {
                continue;
            }
            String[] parts = trimmed.substring("COPY ".length()).trim().split("\\s+");
            for (String part : parts) {
                if (part.isEmpty() || part.startsWith("/") || part.startsWith("--")) {
                    continue;
                }
                sources.add(part);
            }
        }
        return sources;
    }

    private boolean matches(String pattern, String source) {
        String normalizedPattern = pattern.endsWith("/")
                ? pattern.substring(0, pattern.length() - 1) : pattern;
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < normalizedPattern.length(); i++) {
            char c = normalizedPattern.charAt(i);
            if (c == '*') {
                regex.append("[^/]*");
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        String normalizedSource = source.endsWith("/")
                ? source.substring(0, source.length() - 1) : source;
        return normalizedSource.matches(regex.toString());
    }
}
