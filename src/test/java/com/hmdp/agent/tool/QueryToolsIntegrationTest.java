package com.hmdp.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hmdp.dto.QueryToolResult;
import com.hmdp.dto.QueryToolResult.Status;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.IQueryToolService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.UserHolder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 只允许在现有隔离测试 Compose 的独立数据库中执行。 */
@SpringBootTest(
    properties = {
      "citylens.reliability.scheduling-enabled=false",
      "hmdp.cdc.enabled=false",
      "hmdp.mq.enabled=false",
      "hmdp.analytics.enabled=false",
      "hmdp.agent.query-tools.timeout-ms=10000"
    })
class QueryToolsIntegrationTest {
  @Autowired private IQueryToolService tools;
  @Autowired private IVoucherOrderService orders;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void isolate() {
    assertEquals("hmdp_optimization_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
  }

  @AfterEach
  void clear() {
    UserHolder.removeUser();
  }

  private void login(long id) {
    UserDTO user = new UserDTO();
    user.setId(id);
    UserHolder.saveUser(user);
  }

  private Map<String, Object> arguments(Object... pairs) {
    Map<String, Object> result = new HashMap<>();
    for (int index = 0; index < pairs.length; index += 2) {
      result.put((String) pairs[index], pairs[index + 1]);
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> rows(QueryToolResult result) {
    assertEquals(Status.OK, result.getStatus(), result.getMessage());
    return (List<Map<String, Object>>) result.getData().get("items");
  }

  @Test
  void realShopAndVoucherQueryRejectsCachedOldPrices() {
    assertFalse(
        rows(tools.execute("searchShops", arguments("typeId", 1, "keyword", "火锅"))).isEmpty());
    Map<String, Object> first =
        rows(tools.execute("listShopVouchers", arguments("shopId", 1, "limit", 20))).stream()
            .filter(row -> "1".equals(row.get("voucherId")))
            .findFirst()
            .get();
    long original = ((Number) first.get("payCents")).longValue();
    try {
      jdbc.update("UPDATE tb_voucher SET pay_value=? WHERE id=1", original - 1);
      Map<String, Object> second =
          rows(tools.execute("listShopVouchers", arguments("shopId", 1, "limit", 20))).stream()
              .filter(row -> "1".equals(row.get("voucherId")))
              .findFirst()
              .get();
      assertEquals(original - 1, ((Number) second.get("payCents")).longValue());
    } finally {
      jdbc.update("UPDATE tb_voucher SET pay_value=? WHERE id=1", original);
    }
  }

  @Test
  void realOrderLookupIsBoundToAuthenticatedOwner() {
    jdbc.update(
        "INSERT INTO tb_voucher(id,shop_id,title,rules,pay_value,actual_value,type,status) "
            + "VALUES(990701,1,'query tools test','rules',0,1000,0,1) "
            + "ON DUPLICATE KEY UPDATE status=1");
    login(1);
    String orderId = orders.orderOrdinaryVoucher(990701L).getData().toString();
    assertTrue(
        rows(tools.execute("listMyOrders", null)).stream()
            .anyMatch(row -> orderId.equals(row.get("orderId"))));
    login(2);
    assertFalse(
        rows(tools.execute("listMyOrders", null)).stream()
            .anyMatch(row -> orderId.equals(row.get("orderId"))));
  }

  @Test
  void realReservationLookupChecksOwnerAndKeepsProcessingState() {
    jdbc.update(
        "INSERT INTO tb_seckill_reservation(order_id,voucher_id,user_id,state) "
            + "VALUES(99070001,990702,1,'PROCESSING') "
            + "ON DUPLICATE KEY UPDATE state='PROCESSING'");
    login(1);
    QueryToolResult own = tools.execute("getClaimStatus", arguments("orderId", "99070001"));
    assertEquals(Status.OK, own.getStatus());
    assertEquals("PROCESSING", own.getData().get("state"));
    assertEquals("NOT_CHECKED", own.getData().get("paymentStatus"));
    login(2);
    assertEquals(
        Status.BUSINESS_REJECTED,
        tools.execute("getClaimStatus", arguments("orderId", "99070001")).getStatus());
  }
}
