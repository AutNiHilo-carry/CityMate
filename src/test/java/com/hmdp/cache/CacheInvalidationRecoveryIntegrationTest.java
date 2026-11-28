package com.hmdp.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.OutboxRelayServiceImpl;
import com.hmdp.utils.CacheClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Real DB/Redis/Outbox; only this shop's invalidation operation is fault-injected. */
@SpringBootTest(
    properties = {
      "citymate.reliability.scheduling-enabled=false",
      "hmdp.cdc.enabled=false",
      "hmdp.mq.enabled=false",
      "hmdp.analytics.enabled=false",
      "citymate.ai.enabled=false",
      "spring.main.allow-bean-definition-overriding=true"
    })
class CacheInvalidationRecoveryIntegrationTest {
  private static final long SHOP = 982041;
  private static final String KEY = "cache:shop:" + SHOP;
  private static final List<Map<String, Object>> CASES = new ArrayList<>();
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired IShopService shops;
  @Autowired TransactionTemplate transactions;
  @Autowired OutboxRelayServiceImpl relay;
  @Autowired FaultInjectingCacheClient cache;
  private AtomicBoolean failing;
  private AtomicInteger injectedFailures;
  private final List<Map<String, Object>> trace = new ArrayList<>();

  @TestConfiguration
  static class FaultConfiguration {
    @Bean
    @Primary
    FaultInjectingCacheClient cacheClient(StringRedisTemplate redis, MemcacheStore shared) {
      return new FaultInjectingCacheClient(redis, shared);
    }
  }

  static class FaultInjectingCacheClient extends CacheClient {
    final AtomicBoolean failing = new AtomicBoolean();
    final AtomicInteger injectedFailures = new AtomicInteger();

    FaultInjectingCacheClient(StringRedisTemplate redis, MemcacheStore shared) {
      super(redis, shared);
    }

    @Override
    public void invalidate(String key) {
      if (KEY.equals(key) && failing.get()) {
        injectedFailures.incrementAndGet();
        throw new RedisConnectionFailureException("injected cache invalidation unavailable");
      }
      super.invalidate(key);
    }
  }

  @BeforeEach
  void prepare() {
    failing = cache.failing;
    injectedFailures = cache.injectedFailures;
    failing.set(false);
    injectedFailures.set(0);
    // Abort before any fixture writes if accidentally pointed at a real business database.
    assertEquals("hmdp_optimization_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
    jdbc.update("DELETE FROM tb_message_inbox");
    jdbc.update("DELETE FROM tb_business_outbox");
    jdbc.update("DELETE FROM tb_shop WHERE id=?", SHOP);
    jdbc.update(
        "INSERT INTO tb_shop(id,name,type_id,images,address,x,y,sold,comments,score) "
            + "VALUES(?,'phase2-old',1,'','isolated fixture',120,30,0,0,40)",
        SHOP);
    // Preserve the monotonic version: resetting it would reuse old Memcached namespaces.
    redis.delete(Arrays.asList(KEY, "lock:cache:" + KEY));
    cache.invalidate(KEY);
  }

  @AfterEach
  void cleanup() {
    failing.set(false);
    cache.invalidate(KEY);
    jdbc.update("DELETE FROM tb_message_inbox");
    jdbc.update("DELETE FROM tb_business_outbox");
    jdbc.update("DELETE FROM tb_shop WHERE id=?", SHOP);
  }

  private String read() {
    Result<?> result = shops.queryById(SHOP);
    assertTrue(result.getSuccess());
    return ((Shop) result.getData()).getName();
  }

  private void update() {
    assertTrue(shops.update(new Shop().setId(SHOP).setName("phase2-new")).getSuccess());
  }

  private long event() {
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM tb_business_outbox "
                + "WHERE kind='SHOP_INVALIDATE' AND aggregate_id=?",
            Integer.class,
            SHOP));
    return jdbc.queryForObject(
        "SELECT id FROM tb_business_outbox " + "WHERE kind='SHOP_INVALIDATE' AND aggregate_id=?",
        Long.class,
        SHOP);
  }

  private Map<String, Object> task(long id) {
    return jdbc.queryForMap(
        "SELECT id,state,attempts,lease_owner,consumed_at " + "FROM tb_business_outbox WHERE id=?",
        id);
  }

  private int inbox(long id) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM tb_message_inbox WHERE event_id=?", Integer.class, id);
  }

  private void snapshot(String stage, Long id) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("stage", stage);
    row.put("observedAt", Instant.now().toString());
    row.put(
        "databaseName",
        jdbc.queryForObject("SELECT name FROM tb_shop WHERE id=?", String.class, SHOP));
    row.put("cacheVersion", redis.opsForValue().get(KEY + ":version"));
    if (id != null) {
      Map<String, Object> task = task(id);
      row.put("eventId", String.valueOf(id));
      row.put("outboxState", task.get("state"));
      row.put("attempts", task.get("attempts"));
      row.put("inboxCount", inbox(id));
    }
    trace.add(row);
  }

  private void completed(String name) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("name", name);
    result.put("passed", true);
    result.put("shopId", String.valueOf(SHOP));
    result.put("injectedFailures", injectedFailures.get());
    result.put("trace", new ArrayList<>(trace));
    CASES.add(result);
  }

  private void makeDue(long id) {
    // Advance only the isolated event's retry eligibility; do not sleep through backoff.
    jdbc.update(
        "UPDATE tb_business_outbox SET next_attempt_at=TIMESTAMPADD(SECOND,-1,NOW()) "
            + "WHERE id=?",
        id);
  }

  @Test
  void committedWriteSurvivesInvalidationFailureAndRecoversThroughSameEvent() {
    assertEquals("phase2-old", read());
    assertNotNull(redis.opsForValue().get(KEY));
    String version = redis.opsForValue().get(KEY + ":version");
    snapshot("warmed", null);
    failing.set(true);
    update();
    long id = event();
    assertEquals(
        "phase2-new",
        jdbc.queryForObject("SELECT name FROM tb_shop WHERE id=?", String.class, SHOP));
    assertEquals("PENDING", task(id).get("state"));
    assertEquals(1, injectedFailures.get());
    assertEquals("phase2-old", read(), "Old reads are allowed before invalidation recovers");
    snapshot("committed_immediate_invalidation_failed", id);
    relay.relayOutboxBatch();
    assertEquals("PENDING", task(id).get("state"));
    assertEquals(1, ((Number) task(id).get("attempts")).intValue());
    assertNull(task(id).get("lease_owner"));
    assertEquals(0, inbox(id), "Failed projection must not acknowledge the work");
    assertEquals(version, redis.opsForValue().get(KEY + ":version"));
    snapshot("relay_failed_work_retained", id);
    failing.set(false);
    makeDue(id);
    relay.relayOutboxBatch();
    assertEquals("DONE", task(id).get("state"));
    assertNotNull(task(id).get("consumed_at"));
    assertEquals(1, inbox(id));
    assertTrue(Long.parseLong(redis.opsForValue().get(KEY + ":version")) > Long.parseLong(version));
    assertEquals("phase2-new", read());
    snapshot("recovered_same_event_current_read", id);
    relay.relayOutboxBatch();
    assertEquals(1, inbox(id));
    assertEquals(id, event(), "Retry must not create a different event identity");
    completed("committed_write_recovers_through_same_event");
  }

  @Test
  void rolledBackWriteDoesNotPublishOrInvalidate() {
    assertEquals("phase2-old", read());
    String version = redis.opsForValue().get(KEY + ":version");
    failing.set(true);
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.execute(
                tx -> {
                  update();
                  throw new IllegalStateException("injected business transaction rollback");
                }));
    assertEquals(
        "phase2-old",
        jdbc.queryForObject("SELECT name FROM tb_shop WHERE id=?", String.class, SHOP));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_business_outbox", Integer.class));
    assertEquals(0, injectedFailures.get(), "afterCommit must never execute after rollback");
    assertEquals(version, redis.opsForValue().get(KEY + ":version"));
    assertEquals("phase2-old", read());
    snapshot("rollback_preserves_old_database_and_cache", null);
    completed("rollback_does_not_publish_or_invalidate");
  }

  @Test
  void oldDatabaseLoadCannotRefillAfterRecoveredInvalidation() throws Exception {
    CountDownLatch captured = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      Future<Shop> old =
          worker.submit(
              () ->
                  cache.queryWithMutex(
                      "cache:shop:",
                      SHOP,
                      Shop.class,
                      id -> {
                        Shop value = shops.getById(id);
                        captured.countDown();
                        try {
                          if (!release.await(15, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("blocked loader was not released");
                          }
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException("loader interrupted", interrupted);
                        }
                        return value;
                      },
                      30L,
                      TimeUnit.MINUTES));
      assertTrue(captured.await(5, TimeUnit.SECONDS));
      failing.set(true);
      update();
      long id = event();
      relay.relayOutboxBatch();
      assertEquals("PENDING", task(id).get("state"));
      snapshot("old_load_blocked_update_committed_retry_pending", id);
      failing.set(false);
      makeDue(id);
      relay.relayOutboxBatch();
      assertEquals("DONE", task(id).get("state"));
      release.countDown();
      assertEquals("phase2-old", old.get(5, TimeUnit.SECONDS).getName());
      assertNull(redis.opsForValue().get(KEY), "Old value must not populate the shared cache");
      assertFalse(read().equals("phase2-old"), "A later request must load the new value");
      assertEquals("phase2-new", read());
      assertEquals(1, inbox(id));
      snapshot("old_load_fenced_new_request_reads_current", id);
      completed("old_load_fenced_after_recovery");
    } finally {
      release.countDown();
      worker.shutdownNow();
      assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @AfterAll
  static void report() throws Exception {
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("generatedAt", Instant.now().toString());
    report.put("database", "hmdp_optimization_test");
    report.put(
        "faultInjection", "Test subclass CacheClient.invalidate throws before real invalidation");
    report.put("realComponents", Arrays.asList("MySQL", "Redis", "Outbox", "Relay", "Inbox"));
    report.put("brokerEnabled", false);
    report.put("backoffNote", "Retry eligibility advanced by test SQL; timing is not benchmarked");
    report.put("passed", CASES.size() == 3);
    report.put("cases", CASES);
    Path path = Paths.get("docs/phase2-cache-recovery-evidence.json");
    Files.createDirectories(path.getParent());
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(), report);
  }
}
