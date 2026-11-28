package com.hmdp.reliability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

class RedisScriptsTest {
  @Test
  void snapshotIsReadOnceAndShaLookupNeverTouchesResourceMetadata() throws Exception {
    String text = "return 1 -- immutable UTF-8 script\n";
    AtomicInteger opens = new AtomicInteger();
    DefaultRedisScript<Long> script =
        RedisScripts.number(
            new AbstractResource() {
              @Override
              public String getDescription() {
                return "one-shot source";
              }

              @Override
              public InputStream getInputStream() {
                assertEquals(1, opens.incrementAndGet(), "Script must be read only at load time");
                return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
              }

              @Override
              public long lastModified() {
                throw new AssertionError("No runtime metadata checks");
              }
            });
    StringBuilder expected = new StringBuilder();
    for (byte value :
        MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8))) {
      expected.append(String.format("%02x", value & 255));
    }
    for (int i = 0; i < 100; i++) {
      assertEquals(expected.toString(), script.getSha1());
      assertEquals(text, script.getScriptAsString());
    }
    assertEquals(1, opens.get());
    assertEquals(Long.class, script.getResultType());
  }

  @Test
  void deployedScriptsKeepExactBodyAndSha() {
    for (String path :
        new String[] {
          "cache-fill.lua",
          "cache-invalidate.lua",
          "consume-code.lua",
          "rate-limit.lua",
          "seckill-compensate.lua",
          "seckill-release.lua",
          "seckill.lua",
          "stream-lag.lua",
          "unlock.lua"
        }) {
      DefaultRedisScript<Long> legacy = new DefaultRedisScript<>();
      legacy.setLocation(new org.springframework.core.io.ClassPathResource(path));
      legacy.setResultType(Long.class);
      DefaultRedisScript<Long> snapshot = RedisScripts.number(path);
      assertEquals(legacy.getScriptAsString(), snapshot.getScriptAsString(), path);
      assertEquals(legacy.getSha1(), snapshot.getSha1(), path);
    }
  }

  @Test
  void missingScriptFailsAtLoadTime() {
    assertThrows(
        IllegalStateException.class, () -> RedisScripts.number("not-a-deployed-script.lua"));
  }

  @Test
  void blankScriptFailsAtLoadTime() {
    assertThrows(
        IllegalStateException.class,
        () -> RedisScripts.number(new ByteArrayResource(" \n".getBytes(StandardCharsets.UTF_8))));
  }
}
