package com.ifoodbuy.spring_ai_demo.service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 简易 BM25 关键词评分，用于与向量检索结果做加权融合（应用层混合检索）。
 * 支持中英文：中文按字符切分，英文按单词切分。
 */
public final class Bm25Scorer {

    private static final Pattern WORD_BOUND = Pattern.compile("[\\p{L}0-9]+|[\\u4e00-\\u9fa5]");
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private Bm25Scorer() {
    }

    /**
     * 对单篇文档计算 query 与 content 的 BM25 分数（基于当前候选集估算 IDF）。
     *
     * @param query       查询串
     * @param content     文档内容
     * @param docFreqs    当前候选集中每个 term 的文档频（包含该 term 的文档数）
     * @param totalDocs   候选集文档总数
     * @param avgDocLen   候选集平均文档长度
     * @return BM25 分数，>= 0
     */
    public static double score(String query, String content,
                               Map<String, Integer> docFreqs, int totalDocs, double avgDocLen) {
        if (query == null || content == null || query.isBlank() || content.isBlank()) {
            return 0.0;
        }
        List<String> qTerms = tokenize(query);
        if (qTerms.isEmpty()) return 0.0;

        List<String> docTerms = tokenize(content);
        int docLen = docTerms.size();
        if (docLen == 0) return 0.0;

        Map<String, Long> tf = docTerms.stream()
                .collect(Collectors.groupingBy(t -> t, Collectors.counting()));

        double sum = 0.0;
        for (String t : qTerms) {
            int df = docFreqs.getOrDefault(t, 0);
            double idf = Math.log(1.0 + (totalDocs - df + 0.5) / (df + 0.5));
            if (idf <= 0) continue;
            long f = tf.getOrDefault(t, 0L);
            if (f == 0) continue;
            double norm = 1.0 - B + B * docLen / Math.max(avgDocLen, 1.0);
            sum += idf * (f * (K1 + 1.0) / (f + K1 * norm));
        }
        return sum;
    }

    /**
     * 在一组文档上预计算：每个 term 的文档频、总文档数、平均长度。
     * 用于批量对同一 query 打 BM25 分。
     */
    public static class DocStats {
        public final Map<String, Integer> docFreqs;
        public final int totalDocs;
        public final double avgDocLen;

        public DocStats(Map<String, Integer> docFreqs, int totalDocs, double avgDocLen) {
            this.docFreqs = docFreqs;
            this.totalDocs = totalDocs;
            this.avgDocLen = avgDocLen;
        }
    }

    public static DocStats buildDocStats(List<String> contents) {
        if (contents == null || contents.isEmpty()) {
            return new DocStats(Map.of(), 0, 0.0);
        }
        Map<String, Set<Integer>> termToDocIds = new HashMap<>();
        double totalLen = 0;
        for (int i = 0; i < contents.size(); i++) {
            List<String> terms = tokenize(contents.get(i));
            totalLen += terms.size();
            for (String t : terms) {
                termToDocIds.computeIfAbsent(t, k -> new HashSet<>()).add(i);
            }
        }
        Map<String, Integer> docFreqs = new HashMap<>();
        for (Map.Entry<String, Set<Integer>> e : termToDocIds.entrySet()) {
            docFreqs.put(e.getKey(), e.getValue().size());
        }
        double avgDocLen = totalLen / contents.size();
        return new DocStats(docFreqs, contents.size(), avgDocLen);
    }

    public static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        var m = WORD_BOUND.matcher(text);
        while (m.find()) {
            String w = m.group().toLowerCase(Locale.ROOT);
            if (!w.isBlank()) out.add(w);
        }
        return out;
    }
}
