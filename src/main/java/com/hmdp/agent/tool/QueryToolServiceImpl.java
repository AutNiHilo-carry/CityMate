package com.hmdp.agent.tool;

import com.hmdp.cache.RequestCacheScope;
import com.hmdp.dto.QueryToolResult;
import com.hmdp.dto.QueryToolResult.Status;
import com.hmdp.dto.Result;
import com.hmdp.dto.ShopSearchDTO;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IQueryToolService;
import com.hmdp.service.ISeckillService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.UserHolder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 仅包装四个只读业务查询；不反射调用任意 Service，不提供领券或管理操作。 */
@Slf4j
@Service
public class QueryToolServiceImpl implements IQueryToolService {
  private static final Set<String> TOOLS =
      Collections.unmodifiableSet(
          new LinkedHashSet<>(
              Arrays.asList("searchShops", "listShopVouchers", "listMyOrders", "getClaimStatus")));

  private final IShopService shops;
  private final IVoucherService vouchers;
  private final IVoucherOrderService orders;
  private final ISeckillService claims;
  private final ThreadPoolExecutor workers =
      new ThreadPoolExecutor(
          2,
          2,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(16),
          task -> {
            Thread thread = new Thread(task, "citymate-query-tool");
            thread.setDaemon(true);
            return thread;
          },
          new ThreadPoolExecutor.AbortPolicy());

  @Value("${hmdp.agent.query-tools.timeout-ms:3000}")
  private long timeoutMillis = 3000;

  public QueryToolServiceImpl(
      IShopService shops,
      IVoucherService vouchers,
      IVoucherOrderService orders,
      ISeckillService claims) {
    this.shops = shops;
    this.vouchers = vouchers;
    this.orders = orders;
    this.claims = claims;
  }

  @Override
  public Set<String> supportedTools() {
    return TOOLS;
  }

  @Override
  public QueryToolResult execute(String tool, Map<String, Object> arguments) {
    if (!TOOLS.contains(tool)) {
      return QueryToolResult.failure(null, Status.INVALID_ARGUMENT, "不支持的查询工具");
    }
    Map<String, Object> input =
        arguments == null ? Collections.emptyMap() : new HashMap<>(arguments);
    UserDTO user = UserHolder.getUser();
    Long userId = user == null ? null : user.getId();
    if (("listMyOrders".equals(tool) || "getClaimStatus".equals(tool))
        && (userId == null || userId <= 0)) {
      return QueryToolResult.failure(tool, Status.UNAUTHENTICATED, "请先登录后查询本人信息");
    }
    Supplier<QueryToolResult> query;
    try {
      query = prepare(tool, input, userId);
    } catch (IllegalArgumentException invalid) {
      return QueryToolResult.failure(tool, Status.INVALID_ARGUMENT, invalid.getMessage());
    }
    Future<QueryToolResult> pending;
    try {
      pending = workers.submit(() -> withIdentity(userId, query));
    } catch (RejectedExecutionException full) {
      return QueryToolResult.failure(tool, Status.BUSY, "查询繁忙，请稍后重试");
    }
    try {
      return pending.get(Math.max(50, Math.min(10000, timeoutMillis)), TimeUnit.MILLISECONDS);
    } catch (TimeoutException timeout) {
      cancel(pending);
      return QueryToolResult.failure(tool, Status.TIMEOUT, "查询超时，本次没有可确认的结果");
    } catch (InterruptedException interrupted) {
      cancel(pending);
      Thread.currentThread().interrupt();
      return QueryToolResult.failure(tool, Status.UNAVAILABLE, "查询已中断");
    } catch (ExecutionException failure) {
      log.warn("只读工具 {} 暂时不可用", tool);
      return QueryToolResult.failure(tool, Status.UNAVAILABLE, "查询服务暂时不可用");
    }
  }

  private Supplier<QueryToolResult> prepare(String tool, Map<String, Object> input, Long userId) {
    switch (tool) {
      case "searchShops":
        ShopSearchDTO criteria = QueryArguments.search(input);
        return () ->
            listResult(
                tool, shops.searchShops(criteria), criteria.getSize(), QueryToolViews::shop, null);
      case "listShopVouchers":
        QueryArguments.only(input, "shopId", "limit");
        long shopId = QueryArguments.integer(input, "shopId", 1, Long.MAX_VALUE);
        int limit = QueryArguments.optionalInt(input, "limit", 10, 1, 20);
        return () -> voucherResult(tool, shopId, limit);
      case "listMyOrders":
        QueryArguments.only(input, "page", "heldOnly");
        int page = QueryArguments.optionalInt(input, "page", 1, 1, 10000);
        boolean heldOnly = QueryArguments.flag(input, "heldOnly", false);
        return () ->
            listResult(
                tool,
                orders.queryMyOrders(page, heldOnly),
                20,
                QueryToolViews::order,
                "订单状态来自本人实时查询；免费券的已领取状态不表示真实收款");
      case "getClaimStatus":
        QueryArguments.only(input, "orderId");
        long orderId = QueryArguments.integer(input, "orderId", 1, Long.MAX_VALUE);
        return () -> {
          Result<?> result = claims.queryReservationStatus(orderId, userId);
          if (!Boolean.TRUE.equals(result.getSuccess())) {
            return QueryToolResult.failure(
                tool, Status.BUSINESS_REJECTED, "未获得秒杀请求进度。普通券订单应查listMyOrders；本次失败不代表未领取或没有权限。");
          }
          return QueryToolResult.ok(
              tool, Collections.unmodifiableMap(QueryToolViews.claim(result.getData())), null);
        };
      default:
        throw new IllegalArgumentException("不支持的查询工具");
    }
  }

  private QueryToolResult listResult(
      String tool,
      Result<?> result,
      int limit,
      Function<Object, Map<String, Object>> view,
      String note) {
    if (!Boolean.TRUE.equals(result.getSuccess())) {
      return rejected(tool);
    }
    Map<String, Object> data = QueryToolViews.rows(result.getData(), limit, view);
    if (note != null) {
      data.put("note", note);
    }
    return QueryToolResult.ok(tool, Collections.unmodifiableMap(data), result.getTotal());
  }

  @SuppressWarnings("unchecked")
  private QueryToolResult voucherResult(String tool, long shopId, int limit) {
    Result<?> result = vouchers.queryVoucherOfShop(shopId);
    if (!Boolean.TRUE.equals(result.getSuccess())) {
      return rejected(tool);
    }
    List<Voucher> catalog = (List<Voucher>) result.getData();
    List<Voucher> selected = catalog.subList(0, Math.min(limit, catalog.size()));
    List<Voucher> rows = new ArrayList<>();
    if (!selected.isEmpty()) {
      // 复用目录与实时活动查询，再批量读当前券元数据，避免把缓存旧价当作现价。
      Map<Long, Voucher> current =
          vouchers.listByIds(selected.stream().map(Voucher::getId).collect(Collectors.toList()))
              .stream()
              .collect(Collectors.toMap(Voucher::getId, voucher -> voucher));
      for (Voucher snapshot : selected) {
        Voucher fresh = current.get(snapshot.getId());
        if (fresh == null
            || !Long.valueOf(shopId).equals(fresh.getShopId())
            || !Integer.valueOf(1).equals(fresh.getStatus())) {
          continue;
        }
        fresh.setStock(snapshot.getStock());
        fresh.setBeginTime(snapshot.getBeginTime());
        fresh.setEndTime(snapshot.getEndTime());
        fresh.setOrderable(
            Integer.valueOf(0).equals(fresh.getType())
                || (Integer.valueOf(1).equals(fresh.getType())
                    && Integer.valueOf(1).equals(snapshot.getType())
                    && Boolean.TRUE.equals(snapshot.getOrderable())));
        rows.add(fresh);
      }
    }
    Map<String, Object> data = QueryToolViews.rows(rows, limit, QueryToolViews::voucher);
    data.put("truncated", catalog.size() > limit);
    data.put("note", "金额已批量复核，库存和窗口来自实时查询；资格尚未核验，不能保证领取成功");
    return QueryToolResult.ok(tool, Collections.unmodifiableMap(data), null);
  }

  private static QueryToolResult rejected(String tool) {
    return QueryToolResult.failure(tool, Status.BUSINESS_REJECTED, "查询未获有效结果，请核对参数或本人权限");
  }

  private QueryToolResult withIdentity(Long userId, Supplier<QueryToolResult> query) {
    UserHolder.removeUser();
    try (RequestCacheScope scope = RequestCacheScope.open()) {
      if (userId != null) {
        UserDTO identity = new UserDTO();
        identity.setId(userId);
        UserHolder.saveUser(identity);
      }
      return query.get();
    } finally {
      UserHolder.removeUser();
    }
  }

  private void cancel(Future<?> pending) {
    pending.cancel(true);
    workers.remove((Runnable) pending);
  }

  @PreDestroy
  public void close() {
    workers.shutdownNow();
  }
}
