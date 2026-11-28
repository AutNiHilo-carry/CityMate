package com.hmdp.reliability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.mapper.BusinessOutboxMapper;
import com.hmdp.messaging.BusinessEvent;
import com.hmdp.service.IBusinessEventService;
import com.hmdp.service.IOrderLifecycleService;
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
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(
    properties = {
      "citymate.reliability.scheduling-enabled=false",
      "citymate.payment.mock-enabled=true",
      "hmdp.mq.enabled=false",
      "hmdp.cdc.enabled=false",
      "hmdp.analytics.enabled=false",
      "citymate.ai.enabled=false"
    })
class SeckillOrderExpiryIntegrationTest {
  private static final long SKU = 940501;
  private static final long USER = 940502;
  private static final List<Map<String, Object>> CASES = new ArrayList<>();
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired VoucherOrderServiceImpl orders;
  @Autowired SeckillServiceImpl coordinator;
  @Autowired IOrderLifecycleService lifecycle;
  @Autowired IBusinessEventService consumer;
  @Autowired BusinessOutboxMapper outbox;
  @Autowired OutboxRelayServiceImpl relay;
  private long orderId;
  private final List<Map<String, Object>> trace = new ArrayList<>();

  @BeforeEach
  void prepare() {
    assertEquals("hmdp_optimization_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
    jdbc.execute("DROP TRIGGER IF EXISTS phase5_reject_event");
    jdbc.execute("DROP TRIGGER IF EXISTS phase5_reject_inbox");
    jdbc.update("DELETE FROM tb_message_inbox");
    jdbc.update("DELETE FROM tb_business_outbox");
    jdbc.update("DELETE FROM tb_voucher_order WHERE voucher_id=?", SKU);
    jdbc.update("DELETE FROM tb_seckill_reservation WHERE voucher_id=?", SKU);
    jdbc.update("DELETE FROM tb_seckill_activity WHERE voucher_id=?", SKU);
    jdbc.update("DELETE FROM tb_seckill_voucher WHERE voucher_id=?", SKU);
    jdbc.update("DELETE FROM tb_voucher WHERE id=?", SKU);
    redis.delete(
        Arrays.asList(
            "stream.orders",
            "seckill:stock:" + SKU,
            "seckill:order:" + SKU,
            "seckill:requests:" + SKU,
            "seckill:activity:" + SKU));
    redis.execute(
        (RedisCallback<Object>)
            c ->
                c.execute(
                    "XGROUP",
                    "CREATE".getBytes(),
                    "stream.orders".getBytes(),
                    "g1".getBytes(),
                    "0".getBytes(),
                    "MKSTREAM".getBytes()));
    jdbc.update(
        "INSERT INTO tb_voucher(id,shop_id,title,rules,pay_value,actual_value,type,status) "
            + "VALUES(?,1,'phase5','one admission per user',300,500,1,1)",
        SKU);
    jdbc.update(
        "INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) "
            + "VALUES(?,1,TIMESTAMPADD(HOUR,-1,NOW()),TIMESTAMPADD(DAY,1,NOW()))",
        SKU);
    jdbc.update("INSERT INTO tb_seckill_activity(voucher_id,total_stock) VALUES(?,1)", SKU);
    assertTrue(coordinator.publishActivity(SKU).getSuccess());
    user(USER);
  }

  @AfterEach
  void clear() {
    jdbc.execute("DROP TRIGGER IF EXISTS phase5_reject_event");
    jdbc.execute("DROP TRIGGER IF EXISTS phase5_reject_inbox");
    UserHolder.removeUser();
  }

  private static void user(long id) {
    UserDTO u = new UserDTO();
    u.setId(id);
    UserHolder.saveUser(u);
  }

  private MapRecord<String, Object, Object> admit() {
    Result accepted = orders.seckillVoucher(SKU);
    assertTrue(accepted.getSuccess());
    orderId = ((Number) accepted.getData()).longValue();
    List<MapRecord<String, Object, Object>> records =
        redis
            .opsForStream()
            .read(
                Consumer.from("g1", "phase5"),
                StreamReadOptions.empty().count(1),
                StreamOffset.create("stream.orders", ReadOffset.lastConsumed()));
    assertEquals(1, records.size());
    return records.get(0);
  }

  private void handle(MapRecord<String, Object, Object> message) {
    ReflectionTestUtils.invokeMethod(coordinator, "handleOrderStreamMessage", message);
  }

  private void create() {
    handle(admit());
    assertNotNull(orders.getById(orderId));
  }

  private void due() {
    jdbc.update(
        "UPDATE tb_voucher_order SET expire_at=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE id=?", orderId);
  }

  private int status() {
    return jdbc.queryForObject(
        "SELECT status FROM tb_voucher_order WHERE id=?", Integer.class, orderId);
  }

  private int stock() {
    return jdbc.queryForObject(
        "SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?", Integer.class, SKU);
  }

  private int events(String kind) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM tb_business_outbox WHERE aggregate_id=? AND kind=?",
        Integer.class,
        orderId,
        kind);
  }

  private long releaseId() {
    assertEquals(1, events("SECKILL_RELEASE"));
    return jdbc.queryForObject(
        "SELECT id FROM tb_business_outbox WHERE aggregate_id=? AND kind='SECKILL_RELEASE'",
        Long.class,
        orderId);
  }

  private void release() {
    consumer.consume(BusinessEvent.from(outbox.selectEventById(releaseId())), "activities");
  }

  private void snapshot(String stage) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("stage", stage);
    row.put("observedAt", Instant.now().toString());
    row.put("orderId", String.valueOf(orderId));
    row.put("orderStatus", orders.getById(orderId) == null ? null : status());
    row.put("databaseStock", stock());
    row.put("redisStock", redis.opsForValue().get("seckill:stock:" + SKU));
    row.put("releaseEvents", events("SECKILL_RELEASE"));
    row.put("closedEvents", events("ORDER_CLOSED"));
    row.put("redisReleaseMarker", redis.opsForValue().get("seckill:released:" + orderId));
    trace.add(row);
  }

  private void completed(String name) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("name", name);
    row.put("passed", true);
    row.put("trace", trace);
    CASES.add(row);
  }

  @Test
  void expiryReturnsBothStocksOnceAndPreservesPurchaseQualification() {
    create();
    assertNotNull(orders.getById(orderId).getExpireAt());
    assertEquals(1, events("ORDER_CREATED"));
    assertEquals(1, events("ORDER_EXPIRE"));
    due();
    assertTrue(lifecycle.closeExpiredOrder(orderId));
    assertFalse(lifecycle.closeExpiredOrder(orderId));
    assertEquals(4, status());
    assertEquals(1, stock());
    assertEquals("0", redis.opsForValue().get("seckill:stock:" + SKU));
    snapshot("cancel_and_database_return_committed_redis_return_pending");
    release();
    release();
    assertEquals("1", redis.opsForValue().get("seckill:stock:" + SKU));
    assertEquals(1, stock());
    assertEquals(1, events("ORDER_CLOSED"));
    assertEquals(orderId, ((Number) orders.seckillVoucher(SKU).getData()).longValue());
    assertEquals("1", redis.opsForValue().get("seckill:stock:" + SKU));
    assertFalse(orders.payMockOrder(orderId, 300L).getSuccess());
    snapshot("duplicate_release_noop_and_user_cannot_readmit");
    completed("timeout_dual_inventory_return_once");
  }

  @Test
  void freeOrderHasNoDeadlineAndNeverReturnsStock() {
    jdbc.update("UPDATE tb_voucher SET pay_value=0 WHERE id=?", SKU);
    create();
    assertEquals(2, status());
    assertNull(orders.getById(orderId).getExpireAt());
    assertEquals(0, events("ORDER_EXPIRE"));
    due();
    assertFalse(lifecycle.closeExpiredOrder(orderId));
    assertEquals(0, stock());
    assertEquals(0, events("SECKILL_RELEASE"));
    snapshot("free_claim_is_not_an_unpaid_order");
    completed("free_order_preserved");
  }

  @Test
  void paymentBeforeDeadlineWinsAndLateTimeoutDoesNotReleaseStock() throws Exception {
    create();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Result> pay =
          pool.submit(
              () -> {
                user(USER);
                try {
                  return orders.payMockOrder(orderId, 300L);
                } finally {
                  UserHolder.removeUser();
                }
              });
      Future<Boolean> close = pool.submit(() -> lifecycle.closeExpiredOrder(orderId));
      assertTrue(pay.get(15, TimeUnit.SECONDS).getSuccess());
      assertFalse(close.get(15, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }
    due();
    assertFalse(lifecycle.closeExpiredOrder(orderId));
    assertEquals(2, status());
    assertEquals(0, stock());
    assertEquals(1, events("ORDER_PAID"));
    assertEquals(0, events("SECKILL_RELEASE"));
    snapshot("paid_order_preserved_after_deadline");
    completed("payment_before_deadline");
  }

  @Test
  void expiredPaymentAndCloseRaceCancelsOnlyOnce() throws Exception {
    create();
    due();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> close = pool.submit(() -> lifecycle.closeExpiredOrder(orderId));
      Future<Result> pay =
          pool.submit(
              () -> {
                user(USER);
                try {
                  return orders.payMockOrder(orderId, 300L);
                } finally {
                  UserHolder.removeUser();
                }
              });
      assertTrue(close.get(15, TimeUnit.SECONDS));
      assertFalse(pay.get(15, TimeUnit.SECONDS).getSuccess());
    } finally {
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }
    release();
    assertEquals(4, status());
    assertEquals(1, stock());
    assertEquals(1, events("ORDER_CLOSED"));
    assertEquals(0, events("ORDER_PAID"));
    snapshot("expired_payment_rejected_one_close");
    completed("expired_payment_close_race");
  }

  @Test
  void missingDeadlineMessageIsCoveredByDatabaseScan() {
    create();
    jdbc.update(
        "DELETE FROM tb_business_outbox WHERE aggregate_id=? AND kind='ORDER_EXPIRE'", orderId);
    due();
    lifecycle.expireOverdueOrders();
    lifecycle.expireOverdueOrders();
    assertEquals(4, status());
    assertEquals(1, stock());
    release();
    assertEquals("1", redis.opsForValue().get("seckill:stock:" + SKU));
    snapshot("periodic_scan_closed_order_without_deadline_message");
    completed("missing_message_database_fallback");
  }

  @Test
  void releaseTaskInsertFailureRollsBackCancellationAndDatabaseReturn() {
    create();
    due();
    jdbc.execute(
        "CREATE TRIGGER phase5_reject_event BEFORE INSERT ON tb_business_outbox "
            + "FOR EACH ROW BEGIN IF NEW.kind='SECKILL_RELEASE' THEN "
            + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='phase5 release event failure'; "
            + "END IF; END");
    assertThrows(RuntimeException.class, () -> lifecycle.closeExpiredOrder(orderId));
    assertEquals(1, status());
    assertEquals(0, stock());
    assertEquals(0, events("ORDER_CLOSED"));
    assertEquals(0, events("SECKILL_RELEASE"));
    snapshot("failed_release_enqueue_rolled_back_close_and_stock");
    completed("cancellation_stock_event_atomicity");
  }

  @Test
  void redisReleaseFailureKeepsTaskAndRecoversWithoutSecondDatabaseReturn() {
    create();
    due();
    assertTrue(lifecycle.closeExpiredOrder(orderId));
    long id = releaseId();
    redis.opsForValue().set("seckill:stock:" + SKU, "invalid");
    relay.relayOutboxBatch();
    assertEquals("PENDING", outbox.selectEventById(id).getState());
    assertEquals(1, stock());
    assertFalse(lifecycle.closeExpiredOrder(orderId));
    snapshot("redis_rejected_release_database_stock_already_returned_once");
    redis.opsForValue().set("seckill:stock:" + SKU, "0");
    jdbc.update(
        "UPDATE tb_business_outbox SET next_attempt_at=TIMESTAMPADD(SECOND,-1,NOW()) WHERE id=?",
        id);
    relay.relayOutboxBatch();
    assertEquals("DONE", outbox.selectEventById(id).getState());
    assertEquals("1", redis.opsForValue().get("seckill:stock:" + SKU));
    assertEquals(1, stock());
    snapshot("same_task_retried_redis_release_completed");
    completed("redis_release_durable_retry");
  }

  @Test
  void redisReleasedBeforeInboxFailureReplaysUsingPermanentMarker() {
    create();
    due();
    assertTrue(lifecycle.closeExpiredOrder(orderId));
    long id = releaseId();
    jdbc.execute(
        "CREATE TRIGGER phase5_reject_inbox BEFORE INSERT ON tb_message_inbox "
            + "FOR EACH ROW BEGIN IF NEW.event_id="
            + id
            + " THEN "
            + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='phase5 inbox failure'; END IF; END");
    assertThrows(RuntimeException.class, this::release);
    assertEquals("1", redis.opsForValue().get("seckill:stock:" + SKU));
    assertEquals("PENDING", outbox.selectEventById(id).getState());
    assertEquals(-1L, redis.getExpire("seckill:released:" + orderId));
    snapshot("redis_return_applied_but_database_ack_rolled_back");
    jdbc.execute("DROP TRIGGER phase5_reject_inbox");
    release();
    assertEquals("1", redis.opsForValue().get("seckill:stock:" + SKU));
    assertEquals(1, stock());
    assertEquals("DONE", outbox.selectEventById(id).getState());
    snapshot("replay_marker_prevented_double_return");
    completed("redis_success_inbox_failure_replay");
  }

  @Test
  void recoveryRefusesPendingReleaseThenRebuildsWithoutDoubleStock() {
    create();
    due();
    assertTrue(lifecycle.closeExpiredOrder(orderId));
    assertFalse(coordinator.recoverActivity(SKU).getSuccess());
    assertEquals("PREPARING", redis.opsForHash().get("seckill:activity:" + SKU, "state"));
    release();
    assertTrue(coordinator.recoverActivity(SKU).getSuccess());
    assertEquals("1", redis.opsForValue().get("seckill:stock:" + SKU));
    release();
    assertEquals("1", redis.opsForValue().get("seckill:stock:" + SKU));
    @SuppressWarnings("unchecked")
    Map<String, Object> report =
        (Map<String, Object>) coordinator.queryInventoryReconciliationReport(SKU).getData();
    assertEquals(true, report.get("database_consistent"));
    snapshot("rebuild_after_completed_return_kept_stock_and_qualification");
    completed("recovery_inventory_coordination");
  }

  @Test
  void returningStockAfterActivityEndDoesNotAllowNewAdmission() {
    create();
    due();
    redis
        .opsForHash()
        .put("seckill:activity:" + SKU, "end", String.valueOf(System.currentTimeMillis() - 10000));
    assertTrue(lifecycle.closeExpiredOrder(orderId));
    release();
    user(USER + 1);
    assertFalse(orders.seckillVoucher(SKU).getSuccess());
    assertEquals("1", redis.opsForValue().get("seckill:stock:" + SKU));
    snapshot("stock_return_does_not_override_activity_window");
    completed("ended_activity_stays_closed");
  }

  @AfterAll
  static void report() throws Exception {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("generatedAt", Instant.now().toString());
    result.put("database", "hmdp_optimization_test");
    result.put("brokerEnabled", false);
    result.put("passed", CASES.size() == 10);
    result.put("cases", CASES);
    new ObjectMapper()
        .writerWithDefaultPrettyPrinter()
        .writeValue(Paths.get("docs/phase5-order-expiry-evidence.json").toFile(), result);
  }
}
