package com.hmdp.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QueryToolViewsTest {
  private Map<String, Object> order(int status, long price) {
    Map<String, Object> source = new HashMap<>();
    source.put("status", status);
    source.put("pay_cents", price);
    source.put("order_id", 9007199254740993L);
    source.put("user_id", 7L);
    source.put("token", "private");
    return QueryToolViews.order(source);
  }

  @Test
  void completedFreeOrderHasAuthoritativeReceivedMeaningWithoutAReceiptClaim() {
    Map<String, Object> value = order(2, 0);
    assertEquals("免费券已领取，不涉及真实收款", value.get("statusMeaning"));
    assertEquals("9007199254740993", value.get("orderId"));
    assertFalse(value.containsKey("user_id"));
    assertFalse(value.containsKey("token"));
  }

  @Test
  void priceIsNotPresentedAsMoneyAlreadyPaid() {
    assertEquals("待付款，尚未支付", order(1, 4500).get("statusMeaning"));
    assertEquals("订单已标记完成，不证明真实付款", order(2, 4500).get("statusMeaning"));
    assertTrue(order(1, 4500).get("priceMeaning").toString().contains("不是实际付款"));
  }

  @Test
  void UnknownOrderStatusCannotBecomeACompletedOrder() {
    assertEquals("订单状态待确认", order(99, 0).get("statusMeaning"));
    assertEquals("订单已取消", order(4, 0).get("statusMeaning"));
  }
}
