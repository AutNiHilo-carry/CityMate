package com.hmdp.service.impl;

import com.hmdp.entity.SeckillReservation;
import com.hmdp.enums.ReservationState;
import com.hmdp.mapper.SeckillReservationMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.reliability.RedisScripts;
import java.util.Arrays;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Exact-reservation compensation shared by the immediate attempt and durable replay. */
@Service
public class SeckillCompensationService {
  private static final DefaultRedisScript<Long> COMPENSATE =
      RedisScripts.number("seckill-compensate.lua");
  private final StringRedisTemplate redis;
  private final TransactionTemplate transactions;
  private final SeckillReservationMapper reservations;
  private final VoucherOrderMapper orders;

  public SeckillCompensationService(
      StringRedisTemplate redis,
      TransactionTemplate transactions,
      SeckillReservationMapper reservations,
      VoucherOrderMapper orders) {
    this.redis = redis;
    this.transactions = transactions;
    this.reservations = reservations;
    this.orders = orders;
  }

  public void compensate(long orderId) {
    transactions.execute(
        tx -> {
          SeckillReservation reservation = reservations.selectReservationForUpdate(orderId);
          if (reservation == null || !ReservationState.FAILED.matches(reservation.getState())) {
            throw new IllegalStateException("仅能补偿已确认失败的预约");
          }
          if (orders.countByOrderId(orderId) > 0) {
            throw new IllegalStateException("订单已经落库，拒绝回补库存");
          }
          String voucher = reservation.getVoucherId().toString();
          Long result =
              redis.execute(
                  COMPENSATE,
                  Arrays.asList(
                      "seckill:stock:" + voucher, "seckill:order:" + voucher,
                      "seckill:requests:" + voucher, "seckill:request:" + orderId),
                  reservation.getUserId().toString(),
                  String.valueOf(orderId));
          if (result == null || result < 0) {
            throw new IllegalStateException("Redis 补偿条件不满足，保留任务重试或复核");
          }
          // 1 = released this owner; 0 = already released or owned by a newer request.
          // Redis effects cannot roll back with MySQL. Replays must therefore be idempotent.
          return null;
        });
  }
}
