package com.hmdp.reliability;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

/** Deployment scripts are immutable snapshots; redeploy to apply changes. */
public final class RedisScripts {
  private RedisScripts() {}

  public static DefaultRedisScript<Long> number(String path) {
    return number(new ClassPathResource(path));
  }

  static DefaultRedisScript<Long> number(Resource resource) {
    try (InputStream input = resource.getInputStream()) {
      String text = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
      if (!StringUtils.hasText(text)) {
        throw new IllegalStateException("Empty Redis script: " + resource.getDescription());
      }
      DefaultRedisScript<Long> script = new DefaultRedisScript<>(text, Long.class);
      // Compute once while loading, before publishing the instance to request threads.
      script.getSha1();
      return script;
    } catch (IOException failure) {
      throw new IllegalStateException(
          "Cannot load Redis script: " + resource.getDescription(), failure);
    }
  }
}
