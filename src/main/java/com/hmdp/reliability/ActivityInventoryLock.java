package com.hmdp.reliability;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Serialize inventory returns with the existing activity rebuild, through the database commit. */
@Component
public class ActivityInventoryLock {
  private final RedissonClient redisson;

  public ActivityInventoryLock(RedissonClient redisson) {
    this.redisson = redisson;
  }

  public void holdUntilCompletion(long voucherId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("库存释放必须在事务内协调");
    }
    RLock lock = redisson.getLock("lock:publish:" + voucherId);
    if (!lock.tryLock()) {
      throw new IllegalStateException("活动库存正在恢复，保留任务重试");
    }
    try {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              if (lock.isHeldByCurrentThread()) {
                lock.unlock();
              }
            }
          });
    } catch (RuntimeException failure) {
      lock.unlock();
      throw failure;
    }
  }
}
