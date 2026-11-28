package com.hmdp.reliability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.service.impl.SeckillServiceImpl;
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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** Independent process load coordination and real database reconciliation; opt-in only. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = {
      "server.port=8081",
      "hmdp.monitor-token=p6-profile-local-only",
      "citymate.reliability.scheduling-enabled=true",
      "hmdp.mq.enabled=true",
      "hmdp.mq.seckill-enabled=true",
      "hmdp.mq.group-prefix=citymate-profile-p6",
      "hmdp.cdc.enabled=false",
      "hmdp.analytics.enabled=false",
      "citymate.ai.enabled=false",
      "logging.level.com.hmdp=info"
    })
@Import(RequestStageProbe.class)
@EnabledIfSystemProperty(named = "citymate.profile", matches = "true")
class IndependentTransactionProfileIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired SeckillServiceImpl coordinator;
  @LocalServerPort int port;
  private final ObjectMapper json = new ObjectMapper();

  private Path exchange() throws Exception {
    String runId = System.getProperty("citymate.profile.run-id", "");
    if (!runId.matches("[a-zA-Z0-9-]{1,64}")) {
      throw new IllegalStateException("A unique profile run id is required");
    }
    Path dir = Paths.get("target", "transaction-profile", runId);
    Files.createDirectories(dir);
    return dir;
  }

  private void signal(Path path, Object value) throws Exception {
    Path temporary = path.resolveSibling(path.getFileName().toString() + ".tmp");
    json.writeValue(temporary.toFile(), value);
    Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
  }

  private Map<String, Object> observations() {
    Map<String, Object> data = new LinkedHashMap<>();
    try {
      java.util.Properties commands =
          redis.execute(
              (org.springframework.data.redis.core.RedisCallback<java.util.Properties>)
                  connection -> connection.info("commandstats"));
      data.put("redisCommandStats", commands);
      data.put(
          "mysqlGlobalStatus",
          jdbc.queryForList(
              "SELECT VARIABLE_NAME,VARIABLE_VALUE FROM performance_schema.global_status "
                  + "WHERE VARIABLE_NAME IN ('Threads_running','Threads_connected',"
                  + "'Innodb_row_lock_time','Innodb_row_lock_waits','Questions')"));
      data.put(
          "sqlDigests",
          jdbc.queryForList(
              "SELECT DIGEST,DIGEST_TEXT,COUNT_STAR,SUM_TIMER_WAIT,SUM_LOCK_TIME,SUM_ROWS_EXAMINED "
                  + "FROM performance_schema.events_statements_summary_by_digest "
                  + "WHERE SCHEMA_NAME='hmdp_optimization_test' "
                  + "ORDER BY SUM_TIMER_WAIT DESC LIMIT 50"));
      data.put("collected", true);
    } catch (RuntimeException failure) {
      data.put("collected", false);
      data.put("errorType", failure.getClass().getSimpleName());
    }
    return data;
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
    Map<String, Object> before = observations();
    Path dir = exchange();
    Map<String, Object> ready = new LinkedHashMap<>();
    ready.put("runId", System.getProperty("citymate.profile.run-id"));
    ready.put("voucherId", sku);
    ready.put("concurrency", concurrency);
    ready.put("requests", requests);
    ready.put("initialStock", initialStock);
    ready.put("tokenPrefix", "p6-" + sku + "-");
    long began = System.nanoTime();
    signal(dir.resolve(sku + "-ready.json"), ready);
    Path input = dir.resolve(sku + "-http.json");
    long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
    while (!Files.isRegularFile(input) && System.nanoTime() < timeout) {
      Thread.sleep(100);
    }
    assertTrue(Files.isRegularFile(input), "Independent load generator did not respond");
    com.fasterxml.jackson.databind.JsonNode payload = json.readTree(input.toFile());
    assertEquals(System.getProperty("citymate.profile.run-id"), payload.path("runId").asText());
    List<Map<String, Object>> samples =
        json.convertValue(
            payload.path("samples"),
            new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
    assertEquals(requests, samples.size());
    double admissionSeconds = payload.path("admissionSeconds").asDouble();
    assertTrue(admissionSeconds > 0);
    for (String token : tokens) {
      redis.delete("login:token:" + token);
    }
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
    result.put("observationsBefore", before);
    result.put("observationsAfter", observations());
    List<Map<String, Object>> stages = RequestStageProbe.take(sku);
    assertEquals(requests, stages.size(), "Every HTTP request needs a server trace");
    for (Map<String, Object> trace : stages) {
      @SuppressWarnings("unchecked")
      Map<String, Object> spans = (Map<String, Object>) trace.get("stages");
      for (String required :
          new String[] {
            "authRead", "authRefresh", "userLimit", "globalLimit", "orderId", "admissionLua"
          }) {
        assertTrue(spans.containsKey(required), "Missing span: " + required);
      }
      assertEquals(0, trace.get("redisFailures"));
    }
    result.put("serverRequestSpans", stages);
    signal(exchange().resolve(sku + "-done.json"), result);
    return result;
  }

  @Test
  void measureFiniteHttpBatchesAndReconcileActualFulfillment() throws Exception {
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("generatedAt", Instant.now().toString());
    report.put("database", jdbc.queryForObject("SELECT DATABASE()", String.class));
    report.put("databaseVersion", jdbc.queryForObject("SELECT VERSION()", String.class));
    java.util.Properties fsync =
        redis.execute(
            (org.springframework.data.redis.core.RedisCallback<java.util.Properties>)
                connection -> connection.getConfig("appendfsync"));
    java.util.Properties aof =
        redis.execute(
            (org.springframework.data.redis.core.RedisCallback<java.util.Properties>)
                connection -> connection.getConfig("appendonly"));
    report.put("redisFsync", fsync.getProperty("appendfsync"));
    report.put("redisAppendOnly", aof.getProperty("appendonly"));
    assertEquals(
        System.getProperty("citymate.profile.redis-fsync", "always"),
        fsync.getProperty("appendfsync"));
    assertEquals("yes", aof.getProperty("appendonly"));
    report.put("testOnlyRequestProbe", true);
    report.put("endpoint", "POST /voucher-order/seckill/{id}");
    report.put("jvm", System.getProperty("java.version"));
    report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
    report.put("jvmMaxMemoryBytes", Runtime.getRuntime().maxMemory());
    report.put("brokerEnabled", true);
    report.put("automaticScheduling", true);
    report.put("generatorSharesApplicationJvm", false);
    report.put("runId", System.getProperty("citymate.profile.run-id"));
    report.put(
        "generatorHost", "Windows host; published localhost port, independent Python process");
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
      Map<String, Object> warmup = round(970600, 4, 20, 20);
      report.put("warmup", warmup);
      assertEquals(true, warmup.get("correct"));
      for (int i = 0; i < 3; i++) {
        Map<String, Object> r = round(970601 + i, new int[] {8, 16, 32}[i], 200, 100);
        rounds.add(r);
        assertEquals(true, r.get("correct"), "Inventory/identity reconciliation failed");
      }
      passed = true;
    } finally {
      report.put("passed", passed);
      Path path = exchange().resolve("application.json");
      Files.createDirectories(path.getParent());
      json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), report);
    }
    assertTrue(passed);
    signal(exchange().resolve("finished.json"), report);
  }
}
