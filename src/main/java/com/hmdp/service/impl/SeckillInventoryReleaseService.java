package com.hmdp.service.impl;

import com.hmdp.enums.OrderStatus;
import com.hmdp.enums.VoucherType;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.reliability.ActivityInventoryLock;
import com.hmdp.reliability.RedisScripts;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/** Redis stock return for cancelled fulfilled orders; distinct from unfulfilled compensation. */
@Service
public class SeckillInventoryReleaseService {
  private static final DefaultRedisScript<Long> RELEASE =
      RedisScripts.number("seckill-release.lua");
  private final VoucherOrderMapper orders;
  private final ActivityInventoryLock inventoryLock;
  private final StringRedisTemplate redis;

  public SeckillInventoryReleaseService(
      VoucherOrderMapper orders, ActivityInventoryLock inventoryLock, StringRedisTemplate redis) {
    this.orders = orders;
    this.inventoryLock = inventoryLock;
    this.redis = redis;
  }

  public void release(long orderId) {
    List<Map<String, Object>> rows = orders.selectExpiryStateForUpdate(orderId);
    if (rows.isEmpty()) {
      throw new IllegalStateException("库存释放订单不存在");
    }
    Map<String, Object> row = rows.get(0);
    if (!OrderStatus.CANCELLED.matches(((Number) row.get("status")).intValue())
        || row.get("voucher_type_snapshot") == null
        || !VoucherType.SECKILL.matches(((Number) row.get("voucher_type_snapshot")).intValue())) {
      throw new IllegalStateException("只能释放已取消秒杀订单的库存");
    }
    long voucherId = ((Number) row.get("voucher_id")).longValue();
    inventoryLock.holdUntilCompletion(voucherId);
    Long result =
        redis.execute(
            RELEASE,
            Arrays.asList(
                "seckill:stock:" + voucherId,
                "seckill:requests:" + voucherId,
                "seckill:released:" + orderId),
            row.get("user_id").toString(),
            String.valueOf(orderId));
    if (result == null || result < 0) {
      throw new IllegalStateException("Redis 库存释放条件不满足，保留任务重试或复核");
    }
  }
}
