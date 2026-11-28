package com.hmdp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Opt-in paid API evaluation. Deliberately does not use the default *Test naming convention. */
class EvidenceSupportLiveEvaluation {
  private Map<String, Object> sample(
      String id, String question, String evidence, String answer, boolean supported) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", id);
    item.put("question", question);
    item.put("evidence", evidence);
    item.put("answer", answer);
    item.put("expected", supported ? "SUPPORTED" : "UNSUPPORTED");
    return item;
  }

  @Test
  void evaluateEightSyntheticAnswersWithRealConfiguredModel() throws Exception {
    Assumptions.assumeTrue("true".equals(System.getenv("CITYMATE_LIVE_REVIEW")));
    String name = System.getenv("DEEPSEEK_MODEL");
    if (name == null || name.trim().isEmpty()) {
      name = "deepseek-flash";
    }
    DeepSeekClient client = new DeepSeekClient(true, System.getenv("DEEPSEEK_API_KEY"), name);
    assertTrue(
        client.isAvailable(), "Configured model credentials are required; values are not logged");
    EvidenceSupportChecker checker = new EvidenceSupportChecker(client);
    List<Map<String, Object>> samples = new ArrayList<>();
    samples.add(sample("ok-booking", "周末可以直接到店吗", "周末到店需要提前电话预约。", "周末到店前需要电话预约。", true));
    samples.add(sample("ok-weekend", "本券周末能用吗", "本券周六和周日不可使用。", "这张券周末不能用。", true));
    samples.add(sample("ok-mixed-price", "券价多少，到店要预约吗", "到店前需预约。", "券价5元。到店前需预约。", true));
    samples.add(sample("bad-refund", "付了钱能退款吗", "付费券需要付款，未付款超时取消。", "购买后随时可以无条件退款。", false));
    samples.add(sample("bad-negation", "周末可以用吗", "本券周末不可使用。", "本券周末可以使用。", false));
    samples.add(sample("bad-live-slots", "现在还有多少停车位", "商户提供停车场，车位数量实时变化。", "现在还剩12个停车位。", false));
    samples.add(sample("bad-shop-scope", "B店的券周末能用吗", "A店规则：周末可使用。", "B店的券周末可以使用。", false));
    samples.add(
        sample("bad-duration", "没付款多久关闭订单", "未支付订单会自动关闭，具体截止时间以订单显示为准。", "订单一定在30分钟后关闭。", false));
    List<Map<String, Object>> results = new ArrayList<>();
    int correct = 0;
    int unsafeAccepted = 0;
    int consecutiveUnverified = 0;
    for (Map<String, Object> sample : samples) {
      Map<String, Object> source = new LinkedHashMap<>();
      source.put("citation", 1);
      source.put("sourceKey", "synthetic-" + sample.get("id"));
      source.put("version", 1);
      source.put("text", sample.get("evidence"));
      List<Map<String, Object>> observations = new ArrayList<>();
      if ("ok-mixed-price".equals(sample.get("id"))) {
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("tool", "listShopVouchers");
        Map<String, Object> voucher = new LinkedHashMap<>();
        voucher.put("voucherId", "synthetic-coupon");
        voucher.put("payCents", 500);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("data", Collections.singletonMap("items", Collections.singletonList(voucher)));
        observation.put("result", result);
        observations.add(observation);
      }
      long started = System.nanoTime();
      String actual =
          checker
              .check(
                  (String) sample.get("question"),
                  (String) sample.get("answer"),
                  Collections.singletonList(source),
                  observations,
                  4000)
              .name();
      Map<String, Object> result = new LinkedHashMap<>(sample);
      result.put("actual", actual);
      result.put("elapsedMs", (System.nanoTime() - started) / 1000000L);
      result.put("correct", actual.equals(sample.get("expected")));
      results.add(result);
      if (actual.equals(sample.get("expected"))) {
        correct++;
      }
      if ("UNSUPPORTED".equals(sample.get("expected")) && "SUPPORTED".equals(actual)) {
        unsafeAccepted++;
      }
      consecutiveUnverified = "UNVERIFIED".equals(actual) ? consecutiveUnverified + 1 : 0;
      if (consecutiveUnverified >= 2) {
        break;
      }
    }
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("generatedAt", Instant.now().toString());
    report.put(
        "scope", "Eight manually labelled synthetic answer reviews; not blind or end-to-end");
    report.put("configuredModel", name);
    report.put("fixtureVersion", "2-tool-contract-aligned");
    report.put("plannedCases", samples.size());
    report.put("reviewRequestsAttempted", results.size());
    report.put("correctVerdicts", correct);
    report.put("unsupportedAnswersAccepted", unsafeAccepted);
    report.put("perReviewBudgetMs", 4000);
    report.put("tokenUsage", "not instrumented");
    report.put("cases", results);
    Path output = Paths.get(System.getProperty("citymate.test.reports", "target/surefire-reports"));
    Files.createDirectories(output);
    new ObjectMapper()
        .writerWithDefaultPrettyPrinter()
        .writeValue(output.resolve("support-live-evaluation.json").toFile(), report);
    assertEquals(
        samples.size(), results.size(), "Evaluation stopped after consecutive unverified reviews");
    assertEquals(
        samples.size(), correct, "Inspect support-live-evaluation.json; do not hide misses");
  }
}
