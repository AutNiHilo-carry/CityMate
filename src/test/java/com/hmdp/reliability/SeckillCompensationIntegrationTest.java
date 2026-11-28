package com.hmdp.reliability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.mapper.BusinessOutboxMapper;
import com.hmdp.messaging.BusinessEvent;
import com.hmdp.messaging.DeliveryRetryPolicy;
import com.hmdp.service.IBusinessEventService;
import com.hmdp.service.IOperationsService;
import com.hmdp.service.impl.OutboxRelayServiceImpl;
import com.hmdp.service.impl.SeckillServiceImpl;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmdp.utils.UserHolder;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    properties = {
      "citymate.reliability.scheduling-enabled=false",
      "hmdp.mq.enabled=false",
      "hmdp.cdc.enabled=false",
      "hmdp.analytics.enabled=false",
      "citymate.ai.enabled=false"
    })
class SeckillCompensationIntegrationTest {
  private static final long SKU = 930501;
  private static final long USER = 930502;
  private static final List<Map<String, Object>> CASES = new ArrayList<>();
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired SeckillServiceImpl coordinator;
  @Autowired VoucherOrderServiceImpl orders;
  @Autowired OutboxRelayServiceImpl relay;
  @Autowired BusinessOutboxMapper outbox;
  @Autowired IBusinessEventService consumer;
  @Autowired IOperationsService operations;
  @Autowired TransactionTemplate transactions;
  private long orderId;
  private final List<Map<String, Object>> trace = new ArrayList<>();

  @BeforeEach
  void prepare() {
    assertEquals("hmdp_optimization_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
    jdbc.execute("DROP TRIGGER IF EXISTS phase4_reject_event");
    jdbc.execute("DROP TRIGGER IF EXISTS phase4_reject_inbox");
    jdbc.update("DELETE FROM tb_message_inbox");
    jdbc.update("DELETE FROM tb_business_outbox");
    jdbc.update("DELETE FROM tb_voucher_order WHERE voucher_id=?", SKU);
    jdbc.update("DELETE FROM tb_seckill_reservation WHERE voucher_id=?", SKU);
    jdbc.update("DELETE FROM tb_seckill_activity WHERE voucher_id=?", SKU);
    jdbc.update("DELETE FROM tb_seckill_voucher WHERE voucher_id=?", SKU);
    jdbc.update("DELETE FROM tb_voucher WHERE id=?", SKU);
    redis.delete(
        Arrays.asList(
            "seckill:stock:" + SKU,
            "seckill:order:" + SKU,
            "seckill:requests:" + SKU,
            "seckill:activity:" + SKU,
            "stream.orders"));
    jdbc.update(
        "INSERT INTO tb_voucher(id,shop_id,title,rules,pay_value,actual_value,type,status) "
            + "VALUES(?,1,'phase4','rules',0,100,1,1)",
        SKU);
    jdbc.update(
        "INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) "
            + "VALUES(?,1,TIMESTAMPADD(HOUR,-1,NOW()),TIMESTAMPADD(DAY,1,NOW()))",
        SKU);
    jdbc.update("INSERT INTO tb_seckill_activity(voucher_id,total_stock) VALUES(?,1)", SKU);
    assertTrue(coordinator.publishActivity(SKU).getSuccess());
    UserDTO user = new UserDTO();
    user.setId(USER);
    UserHolder.saveUser(user);
    Result result = orders.seckillVoucher(SKU);
    assertTrue(result.getSuccess());
    orderId = ((Number) result.getData()).longValue();
    // Persist a reviewed, unfulfilled admission; these tests begin at manual failure confirmation.
    jdbc.update(
        "INSERT INTO tb_seckill_reservation(order_id,voucher_id,user_id,state) "
            + "VALUES(?,?,?,'REVIEW')",
        orderId,
        SKU,
        USER);
    redis.opsForHash().put("seckill:request:" + orderId, "state", "REVIEW");
  }

  @AfterEach
  void cleanup() {
    jdbc.execute("DROP TRIGGER IF EXISTS phase4_reject_event");
    jdbc.execute("DROP TRIGGER IF EXISTS phase4_reject_inbox");
    UserHolder.removeUser();
  }

  private long eventId() {
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM tb_business_outbox "
                + "WHERE kind='SECKILL_COMPENSATE' AND aggregate_id=?",
            Integer.class,
            orderId));
    return jdbc.queryForObject(
        "SELECT id FROM tb_business_outbox " + "WHERE kind='SECKILL_COMPENSATE' AND aggregate_id=?",
        Long.class,
        orderId);
  }

  private String state(long id) {
    return jdbc.queryForObject("SELECT state FROM tb_business_outbox WHERE id=?", String.class, id);
  }

  private int inbox(long id) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM tb_message_inbox "
            + "WHERE event_id=? AND consumer_group='activities'",
        Integer.class,
        id);
  }

  private void due(long id) {
    jdbc.update(
        "UPDATE tb_business_outbox SET next_attempt_at=TIMESTAMPADD(SECOND,-1,NOW()) "
            + "WHERE id=?",
        id);
  }

  private void snapshot(String stage, Long id) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("stage", stage);
    row.put("observedAt", Instant.now().toString());
    row.put("orderId", String.valueOf(orderId));
    row.put("reservation", coordinator.queryReservationState(orderId));
    row.put("redisStock", redis.opsForValue().get("seckill:stock:" + SKU));
    row.put("redisOwner", redis.opsForHash().get("seckill:requests:" + SKU, String.valueOf(USER)));
    row.put(
        "databaseStock",
        jdbc.queryForObject(
            "SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?", Integer.class, SKU));
    if (id != null) {
      row.put("eventId", String.valueOf(id));
      row.put("eventState", state(id));
      row.put("inbox", inbox(id));
    }
    trace.add(row);
  }

  private void completed(String name) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("name", name);
    row.put("passed", true);
    row.put("trace", new ArrayList<>(trace));
    CASES.add(row);
  }

  private long scheduleWithRedisFailure() {
    redis.opsForValue().set("seckill:stock:" + SKU, "invalid");
    assertFalse(coordinator.markReservationFailedAndCompensateRedis(orderId).getSuccess());
    assertEquals("FAILED", coordinator.queryReservationState(orderId));
    return eventId();
  }

  @Test
  void invalidNumericStockDoesNotDestroyTheOwnerNeededForRetry() {
    for (String invalid : Arrays.asList("0.5", "9223372036854775807")) {
      redis.opsForValue().set("seckill:stock:" + SKU, invalid);
      assertFalse(coordinator.markReservationFailedAndCompensateRedis(orderId).getSuccess());
      assertEquals(invalid, redis.opsForValue().get("seckill:stock:" + SKU));
      assertEquals(
          String.valueOf(orderId),
          redis.opsForHash().get("seckill:requests:" + SKU, String.valueOf(USER)));
      assertTrue(redis.opsForSet().isMember("seckill:order:" + SKU, String.valueOf(USER)));
      assertEquals(0, inbox(eventId()));
    }
    redis.opsForValue().set("seckill:stock:" + SKU, "0");
    relay.relayOutboxBatch();
    assertEquals("1", redis.opsForValue().get("seckill:stock:" + SKU));
    assertEquals("DONE", state(eventId()));
    assertEquals(1, inbox(eventId()));
  }

  @Test
  void failedRedisCompensationRecoversThroughOneDurableEvent() {
    long id = scheduleWithRedisFailure();
    assertFalse(coordinator.markReservationFailedAndCompensateRedis(orderId).getSuccess());
    assertEquals(id, eventId());
    relay.relayOutboxBatch();
    assertEquals("PENDING", state(id));
    assertEquals(0, inbox(id));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT attempts FROM tb_business_outbox WHERE id=?", Integer.class, id));
    snapshot("failed_state_and_event_retained_after_redis_rejection", id);
    redis.opsForValue().set("seckill:stock:" + SKU, "0");
    due(id);
    relay.relayOutboxBatch();
    assertEquals("DONE", state(id));
    assertEquals(1, inbox(id));
    assertEquals("1", redis.opsForValue().get("seckill:stock:" + SKU));
    assertTrue(coordinator.markReservationFailedAndCompensateRedis(orderId).getSuccess());
    assertEquals(id, eventId());
    assertEquals("1", redis.opsForValue().get("seckill:stock:" + SKU));
    snapshot("same_event_completed_exactly_one_release", id);
    completed("redis_failure_durable_retry");
  }

  @Test
  void failedStateAndEventRollBackTogetherIfEnqueueFails() {
    jdbc.execute(
        "CREATE TRIGGER phase4_reject_event BEFORE INSERT ON tb_business_outbox "
            + "FOR EACH ROW BEGIN IF NEW.kind='SECKILL_COMPENSATE' THEN "
            + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='phase4 event failure'; END IF; END");
    assertThrows(
        RuntimeException.class, () -> coordinator.markReservationFailedAndCompensateRedis(orderId));
    assertEquals("REVIEW", coordinator.queryReservationState(orderId));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_business_outbox", Integer.class));
    assertEquals("0", redis.opsForValue().get("seckill:stock:" + SKU));
    snapshot("event_insert_failure_rolled_back_failed_state", null);
    completed("failed_state_and_event_atomicity");
  }

  @Test
  void redisSuccessBeforeInboxRollbackCanBeReplayedWithoutDoubleRelease() {
    long id = scheduleWithRedisFailure();
    redis.opsForValue().set("seckill:stock:" + SKU, "0");
    jdbc.execute(
        "CREATE TRIGGER phase4_reject_inbox BEFORE INSERT ON tb_message_inbox "
            + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='phase4 inbox failure'");
    relay.relayOutboxBatch();
    assertEquals("PENDING", state(id));
    assertEquals(0, inbox(id));
    assertEquals("1", redis.opsForValue().get("seckill:stock:" + SKU));
    snapshot("redis_release_survived_inbox_transaction_rollback", id);
    jdbc.execute("DROP TRIGGER phase4_reject_inbox");
    due(id);
    relay.relayOutboxBatch();
    assertEquals("DONE", state(id));
    assertEquals(1, inbox(id));
    assertEquals("1", redis.opsForValue().get("seckill:stock:" + SKU));
    snapshot("replay_acknowledged_without_second_release", id);
    completed("redis_success_database_ack_failure");
  }

  @Test
  void oldCompensationCannotReleaseNewerUserReservation() {
    assertTrue(coordinator.markReservationFailedAndCompensateRedis(orderId).getSuccess());
    long id = eventId();
    Result next = orders.seckillVoucher(SKU);
    assertTrue(next.getSuccess());
    long newer = ((Number) next.getData()).longValue();
    assertTrue(newer != orderId);
    relay.relayOutboxBatch();
    assertEquals("DONE", state(id));
    assertEquals("0", redis.opsForValue().get("seckill:stock:" + SKU));
    assertEquals(
        String.valueOf(newer),
        redis.opsForHash().get("seckill:requests:" + SKU, String.valueOf(USER)));
    assertEquals("PROCESSING", redis.opsForHash().get("seckill:request:" + newer, "state"));
    snapshot("old_task_preserved_newer_owner_and_preoccupation", id);
    completed("old_task_preserves_newer_reservation");
  }

  @Test
  void committedOrderRejectsFailureConfirmationAndDelayedCompensation() {
    jdbc.update(
        "INSERT INTO tb_voucher_order(id,user_id,voucher_id) VALUES(?,?,?)", orderId, USER, SKU);
    assertFalse(coordinator.markReservationFailedAndCompensateRedis(orderId).getSuccess());
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_business_outbox", Integer.class));
    assertEquals("REVIEW", coordinator.queryReservationState(orderId));
    // Also protect the replay path if inconsistent data is introduced after scheduling.
    jdbc.update("DELETE FROM tb_voucher_order WHERE id=?", orderId);
    long id = scheduleWithRedisFailure();
    jdbc.update(
        "INSERT INTO tb_voucher_order(id,user_id,voucher_id) VALUES(?,?,?)", orderId, USER, SKU);
    redis.opsForValue().set("seckill:stock:" + SKU, "0");
    relay.relayOutboxBatch();
    assertEquals("PENDING", state(id));
    assertEquals(0, inbox(id));
    assertEquals("0", redis.opsForValue().get("seckill:stock:" + SKU));
    snapshot("inconsistent_existing_order_blocked_release", id);
    completed("existing_order_protection");
  }

  @Test
  void outerBusinessRollbackCannotReleaseRedisOrLeaveAnEvent() {
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.execute(
                tx -> {
                  assertTrue(
                      coordinator.markReservationFailedAndCompensateRedis(orderId).getSuccess());
                  assertEquals("0", redis.opsForValue().get("seckill:stock:" + SKU));
                  throw new IllegalStateException("outer rollback");
                }));
    assertEquals("REVIEW", coordinator.queryReservationState(orderId));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_business_outbox", Integer.class));
    assertEquals("0", redis.opsForValue().get("seckill:stock:" + SKU));
    snapshot("outer_rollback_no_redis_side_effect", null);
    completed("outer_transaction_rollback");
  }

  @Test
  void exhaustedRetryRequiresReviewAndManualRedriveUsesSameEvent() {
    long id = scheduleWithRedisFailure();
    for (int i = 0; i < DeliveryRetryPolicy.MAX_SEND_ATTEMPTS; i++) {
      due(id);
      relay.relayOutboxBatch();
    }
    assertEquals("REVIEW", state(id));
    snapshot("bounded_retries_quarantined", id);
    redis.opsForValue().set("seckill:stock:" + SKU, "0");
    relay.relayOutboxBatch();
    assertEquals("REVIEW", state(id));
    assertTrue(operations.retryBusinessEvent(id).getSuccess());
    relay.relayOutboxBatch();
    assertEquals("DONE", state(id));
    assertEquals(id, eventId());
    assertEquals(1, inbox(id));
    assertEquals("1", redis.opsForValue().get("seckill:stock:" + SKU));
    snapshot("same_event_manual_redrive_completed", id);
    completed("retry_exhaustion_and_review");
  }

  @Test
  void concurrentDuplicateEventsReleaseOnceAndRecordOneInbox() throws Exception {
    long id = scheduleWithRedisFailure();
    redis.opsForValue().set("seckill:stock:" + SKU, "0");
    BusinessEvent event = BusinessEvent.from(outbox.selectEventById(id));
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<?>> results = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        results.add(pool.submit(() -> consumer.consume(event, "activities")));
      }
      for (Future<?> result : results) {
        result.get(20, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }
    assertEquals("DONE", state(id));
    assertEquals(1, inbox(id));
    assertEquals("1", redis.opsForValue().get("seckill:stock:" + SKU));
    snapshot("eight_deliveries_one_release_one_inbox", id);
    completed("concurrent_duplicate_compensation");
  }

  @AfterAll
  static void report() throws Exception {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("generatedAt", Instant.now().toString());
    result.put("database", "hmdp_optimization_test");
    result.put("brokerEnabled", false);
    result.put("passed", CASES.size() == 8);
    result.put("cases", CASES);
    new ObjectMapper()
        .writerWithDefaultPrettyPrinter()
        .writeValue(Paths.get("docs/phase4-compensation-evidence.json").toFile(), result);
  }
}
