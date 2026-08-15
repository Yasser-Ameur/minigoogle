package com.minigoogle.semantic.encoder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BERT WordPiece tokenizer, matching the reference implementation closely enough
 * that token ids agree with the model's training-time preprocessing.
 *
 * <p>Written here rather than pulled in as a dependency: it is ~150 lines of
 * well-specified behaviour, and the alternative (a tokenizers binding) would add
 * a second native library for one function.</p>
 *
 * <p>The pipeline is the standard two stages:</p>
 * <ol>
 *   <li><b>Basic tokenization</b> — NFD-normalize and strip combining marks,
 *       lowercase, split on whitespace, then split punctuation into its own
 *       tokens and isolate CJK characters.</li>
 *   <li><b>WordPiece</b> — greedy longest-match-first against the vocabulary,
 *       with continuation pieces prefixed {@code ##}. A word no prefix of which
 *       is in the vocabulary becomes {@code [UNK]}.</li>
 * </ol>
 *
 * <p>Getting this wrong is silent: a mismatched tokenizer still produces ids and
 * the model still returns vectors, they are just the wrong vectors. The
 * round-trip assertions in {@code WordPieceTokenizerTest} exist for that reason.</p>
 */
public final class WordPieceTokenizer {

    private static final String CLS = "[CLS]";
    private static final String SEP = "[SEP]";
    private static final String PAD = "[PAD]";
    private static final String UNK = "[UNK]";
    private static final int MAX_CHARS_PER_WORD = 100;

    private final Map<String, Integer> vocab;
    private final int clsId;
    private final int sepId;
    private final int padId;
    private final int unkId;

    private WordPieceTokenizer(Map<String, Integer> vocab) {
        this.vocab = vocab;
        this.clsId = require(vocab, CLS);
        this.sepId = require(vocab, SEP);
        this.padId = require(vocab, PAD);
        this.unkId = require(vocab, UNK);
    }

    private static int require(Map<String, Integer> vocab, String token) {
        Integer id = vocab.get(token);
        if (id == null) {
            throw new IllegalArgumentException("vocabulary is missing the required token " + token);
        }
        return id;
    }

    /** Loads a {@code vocab.txt} whose line number is the token id. */
    public static WordPieceTokenizer fromVocabFile(Path vocabFile) throws IOException {
        List<String> lines = Files.readAllLines(vocabFile, StandardCharsets.UTF_8);
        Map<String, Integer> vocab = new HashMap<>(lines.size() * 2);
        for (int i = 0; i < lines.size(); i++) {
            // Only strip the line terminator: a vocabulary can legitimately
            // contain tokens that are themselves whitespace-looking.
            String token = lines.get(i).replace("\r", "");
            vocab.putIfAbsent(token, i);
        }
        return new WordPieceTokenizer(vocab);
    }

    /** One tokenized sequence, already padded to {@code maxLength}. */
    public record Encoding(long[] inputIds, long[] attentionMask, long[] tokenTypeIds) {
        public int length() {
            return inputIds.length;
        }
    }

    /**
     * Encodes {@code text} as {@code [CLS] tokens... [SEP]}, truncated to fit
     * {@code maxLength} and zero-padded to exactly that length.
     */
    public Encoding encode(String text, int maxLength) {
        List<Integer> pieces = new ArrayList<>(maxLength);
        // Two slots are reserved for [CLS] and [SEP].
        int budget = maxLength - 2;

        outer:
        for (String word : basicTokenize(text)) {
            for (int id : wordPiece(word)) {
                if (pieces.size() >= budget) {
                    break outer;
                }
                pieces.add(id);
            }
        }

        long[] ids = new long[maxLength];
        long[] mask = new long[maxLength];
        long[] types = new long[maxLength];   // single-segment input: all zeros

        int pos = 0;
        ids[pos] = clsId;
        mask[pos] = 1;
        pos++;
        for (int id : pieces) {
            ids[pos] = id;
            mask[pos] = 1;
            pos++;
        }
        ids[pos] = sepId;
        mask[pos] = 1;
        pos++;
        while (pos < maxLength) {
            ids[pos] = padId;
            mask[pos] = 0;
            pos++;
        }
        return new Encoding(ids, mask, types);
    }

    /** Lowercases, strips accents, and splits on whitespace and punctuation. */
    List<String> basicTokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        String cleaned = stripAccents(text.toLowerCase(Locale.ROOT));

        StringBuilder current = new StringBuilder();
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (Character.isWhitespace(c) || c == 0 || c == 0xFFFD) {
                flush(tokens, current);
            } else if (isPunctuation(c) || isCjk(c)) {
                // Punctuation and CJK characters are tokens in their own right.
                flush(tokens, current);
                tokens.add(String.valueOf(c));
            } else if (Character.isISOControl(c)) {
                flush(tokens, current);
            } else {
                current.append(c);
            }
        }
        flush(tokens, current);
        return tokens;
    }

    private static void flush(List<String> tokens, StringBuilder buffer) {
        if (buffer.length() > 0) {
            tokens.add(buffer.toString());
            buffer.setLength(0);
        }
    }

    /** Greedy longest-match-first WordPiece over one whitespace-delimited word. */
    List<Integer> wordPiece(String word) {
        List<Integer> ids = new ArrayList<>();
        if (word.isEmpty()) {
            return ids;
        }
        if (word.length() > MAX_CHARS_PER_WORD) {
            ids.add(unkId);
            return ids;
        }

        int start = 0;
        List<Integer> collected = new ArrayList<>();
        while (start < word.length()) {
            int end = word.length();
            Integer matched = null;
            while (start < end) {
                String piece = start == 0 ? word.substring(start, end) : "##" + word.substring(start, end);
                Integer id = vocab.get(piece);
                if (id != null) {
                    matched = id;
                    break;
                }
                end--;
            }
            if (matched == null) {
                // No prefix of the remainder is in the vocabulary: the whole
                // word is unknown, not just this suffix.
                ids.add(unkId);
                return ids;
            }
            collected.add(matched);
            start = end;
        }
        ids.addAll(collected);
        return ids;
    }

    private static String stripAccents(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (Character.getType(c) != Character.NON_SPACING_MARK) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** BERT treats ASCII symbols as punctuation in addition to Unicode categories. */
    private static boolean isPunctuation(char c) {
        if ((c >= 33 && c <= 47) || (c >= 58 && c <= 64)
                || (c >= 91 && c <= 96) || (c >= 123 && c <= 126)) {
            return true;
        }
        int type = Character.getType(c);
        return type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF);
    }

    public int vocabularySize() {
        return vocab.size();
    }

    /** Exposed for tests; production code goes through {@link #encode}. */
    Integer idOf(String token) {
        return vocab.get(token);
    }
}
