package com.hmdp.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.hmdp.service.ICacheCheckpointService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(
    properties = {"citymate.reliability.scheduling-enabled=false", "hmdp.cdc.enabled=false"})
class CanalPauseRecoveryIntegrationTest {
  private static final long SHOP_ID = 900209;
  private static final String KEY = "cache:shop:" + SHOP_ID;

  @Autowired private Environment settings;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StringRedisTemplate redis;
  @Autowired private RedissonClient redisson;
  @Autowired private CacheChangeProjector projector;
  @Autowired private ICacheCheckpointService checkpoints;

  @Test
  void blockedRealCanalBatchIsReplayedByReplacementConsumer() throws Exception {
    assertEquals("hmdp_optimization_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
    String marker = "cdc-pause-" + System.nanoTime();
    AtomicInteger failures = new AtomicInteger();
    CacheChangeProjector failing = spy(projector);
    doAnswer(
            call -> {
              CanalEntry.Entry entry = call.getArgument(0);
              if (entry.getEntryType() == CanalEntry.EntryType.ROWDATA
                  && "tb_shop".equals(entry.getHeader().getTableName())
                  && CanalEntry.RowChange.parseFrom(entry.getStoreValue()).getRowDatasList()
                      .stream()
                      .flatMap(row -> row.getAfterColumnsList().stream())
                      .anyMatch(
                          column ->
                              "name".equals(column.getName())
                                  && marker.equals(column.getValue()))) {
                failures.incrementAndGet();
                throw new IllegalStateException("Injected CDC projection failure");
              }
              return call.callRealMethod();
            })
        .when(failing)
        .projectEntryChanges(any());

    SimpleMeterRegistry paused = new SimpleMeterRegistry();
    SimpleMeterRegistry recovered = new SimpleMeterRegistry();
    try {
      CanalCacheConsumer first = worker(failing, paused);
      try {
        first.start();
        await(() -> gauge(paused, "connected") == 1, "first worker connected");
        jdbc.update(
            "INSERT INTO tb_shop(id,name,type_id,images,area,address,x,y,"
                + "avg_price,sold,comments,score) VALUES(?, ?,1,'img','test','test',1,1,1,1,1,50)",
            SHOP_ID,
            "cdc-before-pause");
        await(() -> cached("cdc-before-pause") && successes(paused) > 0, "baseline acknowledged");
        jdbc.update("UPDATE tb_shop SET name=? WHERE id=?", marker, SHOP_ID);
        await(
            () -> gauge(paused, "blocked") == 1 && gauge(paused, "connected") == 0,
            "worker paused after repeated projection failures");
        assertEquals(2, failures.get());
        assertEquals(2.0, gauge(paused, "consecutive.failures"));
        assertTrue(cached("cdc-before-pause"), "failed change did not reach Redis");
      } finally {
        first.stop();
      }

      String destination = settings.getRequiredProperty("hmdp.cdc.destination");
      await(
          () -> !redisson.getLock("lock:cache-cdc:" + destination).isLocked(),
          "paused worker released leadership");
      CanalCacheConsumer replacement = worker(projector, recovered);
      try {
        replacement.start();
        await(() -> cached(marker) && successes(recovered) > 0, "failed change replayed and ACKed");
        assertEquals(0.0, gauge(recovered, "blocked"));
        String after = "cdc-after-pause-" + System.nanoTime();
        jdbc.update("UPDATE tb_shop SET name=? WHERE id=?", after, SHOP_ID);
        await(() -> cached(after), "subsequent change projected");
      } finally {
        replacement.stop();
      }
    } finally {
      paused.close();
      recovered.close();
      jdbc.update("DELETE FROM tb_shop WHERE id=?", SHOP_ID);
    }
  }

  private CanalCacheConsumer worker(CacheChangeProjector engine, SimpleMeterRegistry metrics) {
    CanalCacheConsumer worker = new CanalCacheConsumer(engine, redisson, metrics);
    ReflectionTestUtils.setField(worker, "cacheCheckpointService", checkpoints);
    ReflectionTestUtils.setField(worker, "enabled", true);
    ReflectionTestUtils.setField(worker, "maxConsecutiveFailures", 2);
    ReflectionTestUtils.setField(worker, "host", settings.getRequiredProperty("hmdp.cdc.host"));
    ReflectionTestUtils.setField(
        worker, "port", settings.getRequiredProperty("hmdp.cdc.port", Integer.class));
    ReflectionTestUtils.setField(
        worker, "destination", settings.getRequiredProperty("hmdp.cdc.destination"));
    ReflectionTestUtils.setField(worker, "schema", "hmdp_optimization_test");
    return worker;
  }

  private boolean cached(String name) {
    String value = redis.opsForValue().get(KEY);
    return value != null && value.contains(name);
  }

  private static double gauge(SimpleMeterRegistry metrics, String suffix) {
    return metrics.get("hmdp.cdc." + suffix).gauge().value();
  }

  private static double successes(SimpleMeterRegistry metrics) {
    return metrics.find("hmdp.cdc.batches").tag("result", "success").counters().stream()
        .mapToDouble(counter -> counter.count())
        .sum();
  }

  private static void await(BooleanSupplier condition, String expectation) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(100);
    }
    assertTrue(condition.getAsBoolean(), expectation);
  }
}
