package com.hmdp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.entity.KnowledgeChunk;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class KnowledgeRetrievalEvaluationTest {
  @Test
  void recordLiteralParaphraseAndUnanswerableResultsWithoutHidingMisses() throws Exception {
    ObjectMapper json = new ObjectMapper();
    JsonNode dataset =
        json.readTree(getClass().getResourceAsStream("/evaluation/retrieval-v1.json"));
    List<KnowledgeChunk> corpus = new ArrayList<>();
    for (JsonNode document : dataset.path("documents")) {
      KnowledgeChunk chunk = new KnowledgeChunk();
      chunk.setChunkId(document.path("id").asLong());
      chunk.setSourceKey(document.path("sourceKey").asText());
      chunk.setVersion(document.path("version").asInt());
      chunk.setTitle(document.path("title").asText());
      chunk.setContent(document.path("content").asText());
      corpus.add(chunk);
    }
    JsonNode regression =
        json.readTree(getClass().getResourceAsStream("/evaluation/retrieval-regression-v2.json"));
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("developmentBaseline", evaluate(dataset, corpus, true));
    report.put("developmentExpanded", evaluate(dataset, corpus, false));
    report.put("additionalBaseline", evaluate(regression, corpus, true));
    report.put("additionalExpanded", evaluate(regression, corpus, false));
    Path output = Paths.get(System.getProperty("citymate.test.reports", "target/surefire-reports"));
    Files.createDirectories(output);
    json.writerWithDefaultPrettyPrinter()
        .writeValue(output.resolve("retrieval-evaluation.json").toFile(), report);
  }

  private Map<String, Object> evaluate(
      JsonNode dataset, List<KnowledgeChunk> corpus, boolean literal) {
    List<Map<String, Object>> cases = new ArrayList<>();
    int answerable = 0;
    int hits = 0;
    int unknown = 0;
    int falsePositives = 0;
    double recall = 0;
    double reciprocalRank = 0;
    for (JsonNode query : dataset.path("queries")) {
      List<Long> relevant = new ArrayList<>();
      query.path("relevant").forEach(id -> relevant.add(id.asLong()));
      List<KnowledgeChunk> matches =
          literal
              ? KnowledgeRanker.rankLiteral(query.path("query").asText(), corpus)
              : KnowledgeRanker.rank(query.path("query").asText(), corpus);
      List<Long> ranked =
          matches.stream().map(KnowledgeChunk::getChunkId).collect(Collectors.toList());
      List<Long> top = ranked.subList(0, Math.min(3, ranked.size()));
      if (query.path("gateTop1").asBoolean()) {
        assertFalse(ranked.isEmpty(), query.path("id").asText());
        assertEquals(relevant.get(0), ranked.get(0), query.path("id").asText());
      }
      if (relevant.isEmpty()) {
        unknown++;
        if (!ranked.isEmpty()) {
          falsePositives++;
        }
      } else {
        answerable++;
        long found = relevant.stream().filter(top::contains).count();
        recall += found / (double) relevant.size();
        if (found > 0) {
          hits++;
        }
        for (int index = 0; index < top.size(); index++) {
          if (relevant.contains(top.get(index))) {
            reciprocalRank += 1.0 / (index + 1);
            break;
          }
        }
      }
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("id", query.path("id").asText());
      result.put("category", query.path("category").asText());
      result.put("query", query.path("query").asText());
      result.put("relevant", relevant);
      result.put("retrievedTop3", new ArrayList<>(top));
      cases.add(result);
    }
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("scope", dataset.path("scope").asText());
    report.put("datasetVersion", dataset.path("version").asInt());
    report.put("answerableQueries", answerable);
    report.put("hitRateAt3", hits / (double) answerable);
    report.put("macroRecallAt3", recall / answerable);
    report.put("mrrAt3", reciprocalRank / answerable);
    report.put("unanswerableQueries", unknown);
    report.put("unanswerableWithAnyRetrieval", falsePositives);
    report.put("cases", cases);
    return report;
  }
}
