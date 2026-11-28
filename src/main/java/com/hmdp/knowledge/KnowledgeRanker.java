package com.hmdp.knowledge;

import com.hmdp.entity.KnowledgeChunk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 对已按业务范围过滤的有限候选做 BM25 排序；分数不是答案可信概率。 */
public final class KnowledgeRanker {
  private KnowledgeRanker() {}

  public static List<KnowledgeChunk> rank(String query, List<KnowledgeChunk> candidates) {
    return rankTerms(KnowledgeQueryTerms.weights(query), candidates);
  }

  /** Original tokenizer-only baseline, retained for comparisons on the same labelled queries. */
  public static List<KnowledgeChunk> rankLiteral(String query, List<KnowledgeChunk> candidates) {
    Map<String, Double> terms = new LinkedHashMap<>();
    KnowledgeText.tokens(query).forEach(term -> terms.put(term, 1.0));
    return rankTerms(terms, candidates);
  }

  private static List<KnowledgeChunk> rankTerms(
      Map<String, Double> terms, List<KnowledgeChunk> candidates) {
    List<Map<String, Integer>> frequencies = new ArrayList<>();
    List<Integer> lengths = new ArrayList<>();
    Map<String, Integer> documentFrequency = new HashMap<>();
    double totalLength = 0;
    for (KnowledgeChunk candidate : candidates) {
      List<String> tokens =
          KnowledgeText.tokens(candidate.getTitle() + " " + candidate.getContent());
      Map<String, Integer> frequency = new HashMap<>();
      for (String token : tokens) {
        frequency.merge(token, 1, Integer::sum);
      }
      for (String token : new HashSet<>(tokens)) {
        documentFrequency.merge(token, 1, Integer::sum);
      }
      frequencies.add(frequency);
      lengths.add(tokens.size());
      totalLength += tokens.size();
    }
    double averageLength = Math.max(1, totalLength / Math.max(1, candidates.size()));
    Map<Long, Double> scores = new HashMap<>();
    for (int index = 0; index < candidates.size(); index++) {
      double score = 0;
      for (Map.Entry<String, Double> termWeight : terms.entrySet()) {
        String term = termWeight.getKey();
        int frequency = frequencies.get(index).getOrDefault(term, 0);
        if (frequency == 0) {
          continue;
        }
        int count = documentFrequency.getOrDefault(term, 0);
        double idf = Math.log(1 + (candidates.size() - count + 0.5) / (count + 0.5));
        double normalization = 1.2 * (0.25 + 0.75 * lengths.get(index) / averageLength);
        score += termWeight.getValue() * idf * frequency * 2.2 / (frequency + normalization);
      }
      if (score > 0) {
        scores.put(candidates.get(index).getChunkId(), score);
      }
    }
    List<KnowledgeChunk> ranked = new ArrayList<>();
    for (KnowledgeChunk candidate : candidates) {
      if (scores.containsKey(candidate.getChunkId())) {
        ranked.add(candidate);
      }
    }
    ranked.sort(
        Comparator.<KnowledgeChunk>comparingDouble(chunk -> scores.get(chunk.getChunkId()))
            .reversed()
            .thenComparing(KnowledgeChunk::getChunkId));
    return ranked;
  }
}
