package com.hmdp.reliability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.service.impl.SeckillServiceImpl;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** Finite HTTP batch baseline with automatic production consumers; not a capacity certification. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "citymate.reliability.scheduling-enabled=true",
      "hmdp.mq.enabled=true",
      "hmdp.mq.seckill-enabled=true",
      "hmdp.mq.group-prefix=citymate-baseline-p6",
      "hmdp.cdc.enabled=false",
      "hmdp.analytics.enabled=false",
      "citymate.ai.enabled=false",
      "logging.level.com.hmdp=info"
    })
@EnabledIfSystemProperty(named = "citymate.baseline", matches = "true")
class TransactionBaselineIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired SeckillServiceImpl coordinator;
  @LocalServerPort int port;
  private final ObjectMapper json = new ObjectMapper();

  private Map<String, Object> request(long sku, String token) {
    long began = System.nanoTime();
    Map<String, Object> row = new LinkedHashMap<>();
    HttpURLConnection connection = null;
    try {
      connection =
          (HttpURLConnection)
              new URL("http://127.0.0.1:" + port + "/voucher-order/seckill/" + sku)
                  .openConnection();
      connection.setRequestMethod("POST");
      connection.setRequestProperty("authorization", token);
      connection.setConnectTimeout(5000);
      connection.setReadTimeout(15000);
      int status = connection.getResponseCode();
      row.put("httpStatus", status);
      InputStream stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
      try (InputStream body = stream) {
        JsonNode result = json.readTree(body);
        if (status == 200 && result.path("success").asBoolean()) {
          row.put("kind", "accepted");
          row.put("orderId", result.path("data").asText());
        } else if (status == 429) {
          row.put("kind", "rate_limited");
        } else if (status == 200 && "库存不足".equals(result.path("errorMsg").asText())) {
          row.put("kind", "sold_out");
        } else {
          row.put("kind", "unexpected_response");
          row.put("error", result.path("errorMsg").asText());
        }
      }
    } catch (Exception failure) {
      row.put("kind", "transport_error");
      row.put("errorType", failure.getClass().getSimpleName());
    } finally {
      row.put("latencyMs", (System.nanoTime() - began) / 1_000_000.0);
      if (connection != null) {
        connection.disconnect();
      }
    }
    return row;
  }

  private long count(String sql, long sku) {
    return jdbc.queryForObject(sql, Long.class, sku);
  }

  private Map<String, Object> round(long sku, int concurrency, int requests, int initialStock)
      throws Exception {
    // Fail before fixture writes if a non-test database is selected.
    assertEquals("hmdp_optimization_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
    assertEquals(0L, count("SELECT COUNT(*) FROM tb_voucher WHERE id=?", sku));
    jdbc.update(
        "INSERT INTO tb_voucher(id,shop_id,title,rules,pay_value,actual_value,type,status) "
            + "VALUES(?,1,'phase6 baseline','isolated fixture',0,100,1,1)",
        sku);
    jdbc.update(
        "INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) "
            + "VALUES(?,?,TIMESTAMPADD(HOUR,-1,NOW()),TIMESTAMPADD(DAY,1,NOW()))",
        sku,
        initialStock);
    jdbc.update(
        "INSERT INTO tb_seckill_activity(voucher_id,total_stock) VALUES(?,?)", sku, initialStock);
    assertTrue(coordinator.publishActivity(sku).getSuccess());
    List<String> tokens = new ArrayList<>();
    for (int i = 0; i < requests; i++) {
      String token = "p6-" + sku + "-" + i;
      tokens.add(token);
      redis.opsForHash().put("login:token:" + token, "id", String.valueOf(sku * 10000 + i));
      redis.expire("login:token:" + token, 10, TimeUnit.MINUTES);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("voucherId", sku);
    result.put("concurrency", concurrency);
    result.put("requests", requests);
    result.put("initialStock", initialStock);
    result.put("startedAt", Instant.now().toString());
    List<Map<String, Object>> samples = new ArrayList<>();
    ExecutorService pool = Executors.newFixedThreadPool(concurrency);
    CountDownLatch start = new CountDownLatch(1);
    long began = System.nanoTime();
    long admissionEnded = began;
    try {
      List<Future<Map<String, Object>>> futures = new ArrayList<>();
      for (String token : tokens) {
        futures.add(
            pool.submit(
                () -> {
                  if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("start timeout");
                  }
                  return request(sku, token);
                }));
      }
      start.countDown();
      for (Future<Map<String, Object>> future : futures) {
        samples.add(future.get(30, TimeUnit.SECONDS));
      }
      admissionEnded = System.nanoTime();
    } finally {
      start.countDown();
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
      for (String token : tokens) {
        redis.delete("login:token:" + token);
      }
    }
    double admissionSeconds = (admissionEnded - began) / 1_000_000_000.0;
    Set<String> acceptedIds = new HashSet<>();
    int accepted = 0;
    int limited = 0;
    int soldOut = 0;
    int errors = 0;
    List<Double> latency = new ArrayList<>();
    for (Map<String, Object> sample : samples) {
      latency.add(((Number) sample.get("latencyMs")).doubleValue());
      String kind = String.valueOf(sample.get("kind"));
      if ("accepted".equals(kind)) {
        accepted++;
        acceptedIds.add(String.valueOf(sample.get("orderId")));
      } else if ("rate_limited".equals(kind)) {
        limited++;
      } else if ("sold_out".equals(kind)) {
        soldOut++;
      } else {
        errors++;
      }
    }
    Collections.sort(latency);
    result.put("admissionBatchSeconds", admissionSeconds);
    result.put("httpResponsesPerSecond", requests / admissionSeconds);
    result.put("acceptedResponses", accepted);
    result.put("uniqueAcceptedRequests", acceptedIds.size());
    result.put("acceptedRequestsPerAdmissionSecond", accepted / admissionSeconds);
    result.put("soldOutResponses", soldOut);
    result.put("rateLimitedResponses", limited);
    result.put("systemErrors", errors);
    result.put("httpSystemErrorRate", errors / (double) requests);
    result.put("allResponseP50Ms", latency.get((int) Math.ceil(requests * 0.50) - 1));
    result.put("allResponseP95Ms", latency.get((int) Math.ceil(requests * 0.95) - 1));
    result.put("allResponseP99Ms", latency.get((int) Math.ceil(requests * 0.99) - 1));
    result.put("samples", samples);
    long lastOrders = -1;
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
    List<Map<String, Object>> backlog = new ArrayList<>();
    boolean drained = false;
    while (System.nanoTime() < until) {
      lastOrders = count("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=?", sku);
      long unfinished =
          count(
              "SELECT COUNT(*) FROM tb_business_outbox WHERE kind='SECKILL_FULFILL' "
                  + "AND aggregate_id IN "
                  + "(SELECT order_id FROM tb_seckill_reservation WHERE voucher_id=?) "
                  + "AND state<>'DONE'",
              sku);
      Map<String, Object> point = new LinkedHashMap<>();
      point.put("secondsSinceStart", (System.nanoTime() - began) / 1_000_000_000.0);
      point.put("orders", lastOrders);
      point.put("unfinishedFulfillmentEvents", unfinished);
      long pending = redis.opsForStream().pending("stream.orders", "g1").getTotalPendingMessages();
      point.put("streamPendingGlobal", pending);
      backlog.add(point);
      if (lastOrders == accepted && unfinished == 0 && pending == 0) {
        drained = true;
        break;
      }
      Thread.sleep(250);
    }
    double completionSeconds = (System.nanoTime() - began) / 1_000_000_000.0;
    long remaining = count("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?", sku);
    long successful =
        count(
            "SELECT COUNT(*) FROM tb_seckill_reservation WHERE voucher_id=? AND state='SUCCESS'",
            sku);
    long inbox =
        count(
            "SELECT COUNT(*) FROM tb_message_inbox i JOIN tb_business_outbox b ON b.id=i.event_id "
                + "JOIN tb_seckill_reservation r ON r.order_id=b.aggregate_id "
                + "WHERE b.kind='SECKILL_FULFILL' "
                + "AND i.consumer_group='fulfillment' AND r.voucher_id=?",
            sku);
    Set<String> persistedIds =
        new HashSet<>(
            jdbc.queryForList(
                "SELECT CAST(id AS CHAR) FROM tb_voucher_order WHERE voucher_id=?",
                String.class,
                sku));
    long duplicateOwners =
        count(
            "SELECT COUNT(*) FROM (SELECT user_id FROM tb_voucher_order "
                + "WHERE voucher_id=? GROUP BY user_id HAVING COUNT(*)>1) duplicates",
            sku);
    String redisRemaining = redis.opsForValue().get("seckill:stock:" + sku);
    boolean correct =
        drained
            && errors == 0
            && accepted > 0
            && accepted == acceptedIds.size()
            && lastOrders == accepted
            && successful == accepted
            && inbox == accepted
            && remaining == initialStock - accepted
            && String.valueOf(remaining).equals(redisRemaining)
            && duplicateOwners == 0
            && persistedIds.equals(acceptedIds);
    result.put("completionObservationSeconds", completionSeconds);
    result.put("observedEndToEndOrdersPerSecond", lastOrders / completionSeconds);
    result.put("completionPollingIntervalMs", 250);
    result.put("orders", lastOrders);
    result.put("successfulReservations", successful);
    result.put("fulfillmentInbox", inbox);
    result.put("databaseRemaining", remaining);
    result.put("redisRemaining", redisRemaining);
    result.put("duplicateOwners", duplicateOwners);
    result.put("acceptedIdsEqualPersistedIds", persistedIds.equals(acceptedIds));
    result.put("backlogObservations", backlog);
    result.put("correct", correct);
    return result;
  }

  @Test
  void measureFiniteHttpBatchesAndReconcileActualFulfillment() throws Exception {
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("generatedAt", Instant.now().toString());
    report.put("database", jdbc.queryForObject("SELECT DATABASE()", String.class));
    report.put("databaseVersion", jdbc.queryForObject("SELECT VERSION()", String.class));
    report.put("endpoint", "POST /voucher-order/seckill/{id}");
    report.put("jvm", System.getProperty("java.version"));
    report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
    report.put("jvmMaxMemoryBytes", Runtime.getRuntime().maxMemory());
    report.put("brokerEnabled", true);
    report.put("automaticScheduling", true);
    report.put("generatorSharesApplicationJvm", true);
    report.put(
        "mode", "Finite closed-loop batches; one sample per concurrency; not maximum capacity");
    report.put(
        "completionRateNote",
        "From first request submission to observed final order; not steady-state throughput");
    report.put(
        "latencyNote",
        "All HTTP responses including sold-out and rate-limited; queue wait before HTTP excluded");
    List<Map<String, Object>> rounds = new ArrayList<>();
    report.put("rounds", rounds);
    boolean passed = false;
    try {
      Map<String, Object> warmup = round(960600, 4, 20, 20);
      report.put("warmup", warmup);
      assertEquals(true, warmup.get("correct"));
      for (int i = 0; i < 3; i++) {
        Map<String, Object> r = round(960601 + i, new int[] {8, 16, 32}[i], 200, 100);
        rounds.add(r);
        assertEquals(true, r.get("correct"), "Inventory/identity reconciliation failed");
      }
      passed = true;
    } finally {
      report.put("passed", passed);
      Path path = Paths.get("docs/phase6-transaction-baseline.json");
      Files.createDirectories(path.getParent());
      json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), report);
    }
    assertTrue(passed);
  }
}
