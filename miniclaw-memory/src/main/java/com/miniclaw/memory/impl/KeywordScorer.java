package com.miniclaw.memory.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * BM25 keyword scorer for memory / session search.
 * Pure local computation — zero API cost, no external dependencies.
 *
 * <p>BM25 formula: Σ IDF(qi) * TF(qi, doc) * (k1+1) / (TF + k1*(1-b + b*docLen/avgLen))
 */
public class KeywordScorer {

    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private final List<String> corpus;
    private final double avgDocLength;

    /**
     * @param corpus all documents in the search space, used to compute IDF and avgDocLength
     */
    public KeywordScorer(List<String> corpus) {
        this.corpus = List.copyOf(corpus);
        this.avgDocLength = corpus.stream()
            .mapToInt(String::length)
            .average()
            .orElse(1.0);
    }

    public double score(String query, String document) {
        if (query == null || query.isBlank() || document == null || document.isBlank()) {
            return 0.0;
        }

        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) return 0.0;

        Map<String, Double> tf = termFrequencies(document);
        double docLen = document.length();

        double score = 0.0;
        for (String token : queryTokens) {
            double freq = tf.getOrDefault(token, 0.0);
            if (freq == 0.0) continue;
            double idf = idf(token);
            double numerator = freq * (K1 + 1);
            double denominator = freq + K1 * (1 - B + B * docLen / avgDocLength);
            score += idf * numerator / denominator;
        }
        return score;
    }

    private double idf(String term) {
        long n = corpus.stream().filter(d -> d.contains(term)).count();
        if (n == 0) return 0.0;
        return Math.log((corpus.size() - n + 0.5) / (n + 0.5) + 1.0);
    }

    private static Map<String, Double> termFrequencies(String doc) {
        List<String> tokens = tokenize(doc);
        if (tokens.isEmpty()) return Map.of();

        Map<String, Long> counts = tokens.stream()
            .collect(Collectors.groupingBy(t -> t, Collectors.counting()));
        double total = tokens.size();
        Map<String, Double> tf = new HashMap<>();
        counts.forEach((k, v) -> tf.put(k, v / total));
        return tf;
    }

    /** Split on whitespace/punctuation + 2-gram for CJK text without word boundaries. */
    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        String[] parts = text.split("[\\s\\p{Punct}\\p{Blank}，。；：！？、（）【】《》\"'\\[\\]{}——…·]+");
        for (String part : parts) {
            if (part.isEmpty()) continue;
            tokens.add(part.toLowerCase());
            if (part.length() >= 2) {
                for (int i = 0; i < part.length() - 1; i++) {
                    tokens.add(part.substring(i, i + 2).toLowerCase());
                }
            }
        }
        return tokens;
    }
}
