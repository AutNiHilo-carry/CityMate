package com.hmdp.reliability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    properties = {
      "citymate.reliability.scheduling-enabled=false",
      "hmdp.mq.enabled=false",
      "hmdp.cdc.enabled=false",
      "hmdp.analytics.enabled=false",
      "citymate.ai.enabled=false"
    })
@EnabledIfSystemProperty(named = "citymate.script-loading-test", matches = "true")
class RedisScriptLoadingIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;

  @Test
  void flushFallsBackToEvalAndPreservesRateLimitState() {
    assertEquals("hmdp_optimization_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
    String key = "phase6:script-loading:rate";
    DefaultRedisScript<Long> script = RedisScripts.number("rate-limit.lua");
    redis.delete(key);
    try {
      redis.execute(
          (RedisCallback<Object>)
              connection -> {
                connection.scriptFlush();
                return null;
              });
      assertFalse(cached(script));
      assertEquals(
          Long.valueOf(1), redis.execute(script, Collections.singletonList(key), "1", "60000"));
      assertTrue(cached(script));
      redis.execute(
          (RedisCallback<Object>)
              connection -> {
                connection.scriptFlush();
                return null;
              });
      assertFalse(cached(script));
      // SCRIPT FLUSH clears executable cache, not the counter: retry must still be denied.
      assertEquals(
          Long.valueOf(0), redis.execute(script, Collections.singletonList(key), "1", "60000"));
      assertTrue(cached(script));
      assertEquals("2", redis.opsForValue().get(key));
      assertTrue(redis.getExpire(key) > 0);
    } finally {
      redis.delete(key);
    }
  }

  private boolean cached(DefaultRedisScript<Long> script) {
    return Boolean.TRUE.equals(
        redis.execute(
            (RedisCallback<Boolean>)
                connection -> connection.scriptExists(script.getSha1()).get(0)));
  }
}
