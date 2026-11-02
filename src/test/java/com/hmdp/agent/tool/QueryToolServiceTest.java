package com.hmdp.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hmdp.dto.QueryToolResult;
import com.hmdp.dto.QueryToolResult.Status;
import com.hmdp.dto.Result;
import com.hmdp.dto.ShopSearchDTO;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.service.ISeckillService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.UserHolder;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class QueryToolServiceTest {
  private final IShopService shops = mock(IShopService.class);
  private final IVoucherService vouchers = mock(IVoucherService.class);
  private final IVoucherOrderService orders = mock(IVoucherOrderService.class);
  private final ISeckillService claims = mock(ISeckillService.class);
  private final QueryToolServiceImpl tools =
      new QueryToolServiceImpl(shops, vouchers, orders, claims);

  @AfterEach
  void clear() {
    UserHolder.removeUser();
    tools.close();
  }

  private UserDTO login(long id) {
    UserDTO user = new UserDTO();
    user.setId(id);
    user.setNickName("not-needed-by-tools");
    UserHolder.saveUser(user);
    return user;
  }

  private Map<String, Object> arguments(Object... pairs) {
    Map<String, Object> result = new HashMap<>();
    for (int index = 0; index < pairs.length; index += 2) {
      result.put((String) pairs[index], pairs[index + 1]);
    }
    return result;
  }

  @Test
  void whitelistCannotInvokeWritesOrAcceptAnInjectedUser() {
    login(7);
    assertEquals(4, tools.supportedTools().size());
    assertEquals(Status.INVALID_ARGUMENT, tools.execute("claimVoucher", null).getStatus());
    assertEquals(
        Status.INVALID_ARGUMENT,
        tools.execute("listMyOrders", arguments("userId", 99)).getStatus());
    assertEquals(
        Status.INVALID_ARGUMENT,
        tools.execute("getClaimStatus", arguments("orderId", "1", "userId", 99)).getStatus());
    verifyNoInteractions(shops, vouchers, orders, claims);
  }

  @Test
  void anonymousPersonalQueriesNeverReachBusinessServices() {
    assertEquals(Status.UNAUTHENTICATED, tools.execute("listMyOrders", null).getStatus());
    assertEquals(
        Status.UNAUTHENTICATED,
        tools.execute("getClaimStatus", arguments("orderId", "1")).getStatus());
    verifyNoInteractions(orders, claims);
  }

  @Test
  void invalidSearchIsRejectedBeforeAnyDatabaseWork() {
    List<Map<String, Object>> invalid =
        Arrays.asList(
            arguments("typeId", 1, "size", 21),
            arguments("typeId", 1, "x", 120),
            arguments("typeId", 1, "x", Double.NaN, "y", 30),
            arguments("typeId", 1, "sort", "distance"),
            arguments("typeId", 1, "sort", "name desc"),
            arguments("typeId", 1, "hasVoucher", "false"),
            arguments("typeId", 1, "minScore", 51),
            arguments("typeId", 1, "maxPrice", -1),
            arguments("typeId", 1.5),
            arguments("typeId", "9223372036854775808"));
    for (Map<String, Object> input : invalid) {
      assertEquals(Status.INVALID_ARGUMENT, tools.execute("searchShops", input).getStatus());
    }
    verifyNoInteractions(shops);
  }

  @Test
  @SuppressWarnings("unchecked")
  void searchPreservesUnitsAndExactIdsWithoutInventingLocation() {
    when(shops.searchShops(any()))
        .thenReturn(
            Result.ok(
                Collections.singletonList(
                    new Shop().setId(9007199254740993L).setAvgPrice(50L).setScore(45)),
                1L));
    QueryToolResult result = tools.execute("searchShops", arguments("typeId", "1", "maxPrice", 50));
    assertEquals(Status.OK, result.getStatus());
    Map<String, Object> row = ((List<Map<String, Object>>) result.getData().get("items")).get(0);
    assertEquals("9007199254740993", row.get("shopId"));
    assertEquals(50L, row.get("avgPriceYuan"));
    assertEquals(45, row.get("scoreTenths"));
    ArgumentCaptor<ShopSearchDTO> criteria = ArgumentCaptor.forClass(ShopSearchDTO.class);
    verify(shops).searchShops(criteria.capture());
    assertNull(criteria.getValue().getX());
    assertEquals(10, criteria.getValue().getSize());
  }

  @Test
  @SuppressWarnings("unchecked")
  void workerUsesAuthenticatedOwnerAndWhitelistsOrderOutput() {
    UserDTO caller = login(7);
    when(orders.queryMyOrders(1, false))
        .thenAnswer(
            invocation -> {
              assertEquals(7L, UserHolder.getUser().getId());
              assertNull(UserHolder.getUser().getNickName());
              return Result.ok(
                  Collections.singletonList(
                      arguments("order_id", "9007199254740993", "user_id", 7, "token", "secret")),
                  1L);
            });
    QueryToolResult result = tools.execute("listMyOrders", null);
    assertEquals(Status.OK, result.getStatus());
    assertSame(caller, UserHolder.getUser());
    Map<String, Object> row = ((List<Map<String, Object>>) result.getData().get("items")).get(0);
    assertEquals(Collections.singletonMap("orderId", "9007199254740993"), row);
  }

  @Test
  void workerCannotLeakPreviousIdentityToNextUserOrAnonymousQuery() {
    ThreadPoolExecutor pool = (ThreadPoolExecutor) ReflectionTestUtils.getField(tools, "workers");
    pool.setCorePoolSize(1);
    pool.setMaximumPoolSize(1);
    when(orders.queryMyOrders(anyInt(), anyBoolean()))
        .thenAnswer(
            invocation -> Result.ok(Collections.singletonList(arguments("order_id", 1L)), 1L));
    login(7);
    assertEquals(Status.OK, tools.execute("listMyOrders", null).getStatus());
    login(8);
    when(claims.queryReservationStatus(1, 8))
        .thenAnswer(
            invocation -> {
              assertEquals(8L, UserHolder.getUser().getId());
              return Result.ok(arguments("order_id", "1", "state", "PROCESSING"));
            });
    assertEquals(Status.OK, tools.execute("getClaimStatus", arguments("orderId", 1)).getStatus());
    UserHolder.removeUser();
    when(shops.searchShops(any()))
        .thenAnswer(
            invocation -> {
              assertNull(UserHolder.getUser());
              return Result.ok(Collections.emptyList(), 0L);
            });
    assertEquals(Status.OK, tools.execute("searchShops", arguments("typeId", 1)).getStatus());
  }

  @Test
  void claimStatesAreNotConfusedWithToolSuccessOrPayment() {
    login(7);
    for (String state : Arrays.asList("PROCESSING", "SUCCESS", "FAILED", "REVIEW")) {
      when(claims.queryReservationStatus(9007199254740993L, 7))
          .thenReturn(
              Result.ok(
                  arguments(
                      "order_id",
                      "9007199254740993",
                      "state",
                      state,
                      "reason",
                      "internal stack trace")));
      QueryToolResult result =
          tools.execute("getClaimStatus", arguments("orderId", "9007199254740993"));
      assertEquals(Status.OK, result.getStatus());
      assertEquals(state, result.getData().get("state"));
      assertEquals("NOT_CHECKED", result.getData().get("paymentStatus"));
      assertFalse(result.getData().containsKey("reason"));
    }
  }

  @Test
  void ownershipRejectionAndServiceFailureRevealNoInternalDetails() {
    login(8);
    when(claims.queryReservationStatus(1, 8)).thenReturn(Result.fail("other user 7 secret"));
    QueryToolResult rejected = tools.execute("getClaimStatus", arguments("orderId", 1));
    assertEquals(Status.BUSINESS_REJECTED, rejected.getStatus());
    assertFalse(rejected.getMessage().contains("secret"));
    assertNull(rejected.getData());
    when(shops.searchShops(any())).thenThrow(new IllegalStateException("jdbc password secret"));
    QueryToolResult unavailable = tools.execute("searchShops", arguments("typeId", 1));
    assertEquals(Status.UNAVAILABLE, unavailable.getStatus());
    assertFalse(unavailable.getMessage().contains("secret"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void vouchersRecheckCurrentPriceAndKeepStockSeparateFromEligibility() {
    Voucher old =
        new Voucher()
            .setId(1L)
            .setShopId(2L)
            .setPayValue(100L)
            .setStock(3)
            .setType(1)
            .setOrderable(true);
    Voucher current =
        new Voucher().setId(1L).setShopId(2L).setStatus(1).setType(1).setPayValue(200L);
    when(vouchers.queryVoucherOfShop(2L))
        .thenReturn(Result.ok(Arrays.asList(old, new Voucher().setId(2L))));
    when(vouchers.listByIds(Collections.singletonList(1L)))
        .thenReturn(Collections.singletonList(current));
    QueryToolResult result = tools.execute("listShopVouchers", arguments("shopId", 2, "limit", 1));
    assertEquals(Status.OK, result.getStatus());
    assertEquals(true, result.getData().get("truncated"));
    Map<String, Object> row = ((List<Map<String, Object>>) result.getData().get("items")).get(0);
    assertEquals(200L, row.get("payCents"));
    assertEquals(3, row.get("stock"));
    assertEquals(true, row.get("availableAtQuery"));
    assertTrue(result.getData().get("note").toString().contains("资格尚未核验"));
    assertEquals(100L, old.getPayValue());
  }

  @Test
  void timeoutInterruptsReadWithoutChangingCallerIdentity() throws Exception {
    UserDTO caller = login(7);
    CountDownLatch finished = new CountDownLatch(1);
    AtomicBoolean interrupted = new AtomicBoolean();
    when(orders.queryMyOrders(1, false))
        .thenAnswer(
            invocation -> {
              try {
                new CountDownLatch(1).await();
                return Result.ok(Collections.emptyList(), 0L);
              } catch (InterruptedException stopped) {
                interrupted.set(true);
                throw stopped;
              } finally {
                finished.countDown();
              }
            });
    ReflectionTestUtils.setField(tools, "timeoutMillis", 80L);
    assertEquals(Status.TIMEOUT, tools.execute("listMyOrders", null).getStatus());
    assertTrue(finished.await(2, TimeUnit.SECONDS));
    assertTrue(interrupted.get());
    assertSame(caller, UserHolder.getUser());
  }

  @Test
  void saturatedPoolRejectsNewWorkInsteadOfGrowingWithoutBound() {
    ThreadPoolExecutor pool = (ThreadPoolExecutor) ReflectionTestUtils.getField(tools, "workers");
    CountDownLatch release = new CountDownLatch(1);
    try {
      for (int index = 0; index < 18; index++) {
        pool.execute(
            () -> {
              try {
                release.await();
              } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
              }
            });
      }
      assertEquals(Status.BUSY, tools.execute("searchShops", arguments("typeId", 1)).getStatus());
      verifyNoInteractions(shops);
    } finally {
      release.countDown();
    }
  }
}
