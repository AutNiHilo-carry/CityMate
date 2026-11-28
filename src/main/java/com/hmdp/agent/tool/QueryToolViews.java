package com.hmdp.agent.tool;

import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 输出字段白名单；不把数据库实体或内部错误记录直接塞入模型上下文。 */
final class QueryToolViews {
  private QueryToolViews() {}

  static Map<String, Object> rows(
      Object data, int limit, Function<Object, Map<String, Object>> view) {
    if (!(data instanceof List)) {
      throw new IllegalStateException("业务查询未返回列表");
    }
    List<?> source = (List<?>) data;
    List<Map<String, Object>> items = new ArrayList<>();
    for (int index = 0; index < Math.min(limit, source.size()); index++) {
      items.add(Collections.unmodifiableMap(view.apply(source.get(index))));
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("items", Collections.unmodifiableList(items));
    result.put("truncated", source.size() > limit);
    return result;
  }

  static Map<String, Object> shop(Object value) {
    Shop shop = (Shop) value;
    Map<String, Object> row = new LinkedHashMap<>();
    put(row, "shopId", shop.getId(), true);
    put(row, "typeId", shop.getTypeId(), true);
    put(row, "name", shop.getName(), false);
    put(row, "area", shop.getArea(), false);
    put(row, "address", shop.getAddress(), false);
    put(row, "avgPriceYuan", shop.getAvgPrice(), false);
    put(row, "scoreTenths", shop.getScore(), false);
    put(row, "distanceMeters", shop.getDistance(), false);
    put(row, "openHours", shop.getOpenHours(), false);
    return row;
  }

  static Map<String, Object> voucher(Object value) {
    Voucher voucher = (Voucher) value;
    Map<String, Object> row = new LinkedHashMap<>();
    put(row, "voucherId", voucher.getId(), true);
    put(row, "shopId", voucher.getShopId(), true);
    put(row, "title", voucher.getTitle(), false);
    put(row, "rules", voucher.getRules(), false);
    put(row, "payCents", voucher.getPayValue(), false);
    put(row, "valueCents", voucher.getActualValue(), false);
    put(row, "type", voucher.getType(), false);
    put(row, "stock", voucher.getStock(), false);
    put(row, "beginTime", voucher.getBeginTime(), false);
    put(row, "endTime", voucher.getEndTime(), false);
    put(row, "availableAtQuery", voucher.getOrderable(), false);
    return row;
  }

  static Map<String, Object> order(Object value) {
    Map<?, ?> source = (Map<?, ?>) value;
    Map<String, Object> row = new LinkedHashMap<>();
    put(row, "orderId", source.get("order_id"), true);
    put(row, "voucherId", source.get("voucher_id"), true);
    put(row, "shopId", source.get("shop_id"), true);
    put(row, "status", source.get("status"), false);
    put(row, "createdAt", source.get("created_at"), false);
    put(row, "title", source.get("title"), false);
    put(row, "payCents", source.get("pay_cents"), false);
    put(row, "voucherType", source.get("voucher_type"), false);
    put(row, "rules", source.get("rules"), false);
    if (source.get("status") instanceof Number) {
      int status = ((Number) source.get("status")).intValue();
      boolean free =
          source.get("pay_cents") instanceof Number
              && ((Number) source.get("pay_cents")).longValue() == 0;
      String meaning;
      switch (status) {
        case 1:
          meaning = "待付款，尚未支付";
          break;
        case 2:
          meaning = free ? "免费券已领取，不涉及真实收款" : "订单已标记完成，不证明真实付款";
          break;
        case 3:
          meaning = "订单标记已核销，外部真实核销未验证";
          break;
        case 4:
          meaning = "订单已取消";
          break;
        case 5:
          meaning = "订单标记退款中，真实退款未验证";
          break;
        case 6:
          meaning = "订单标记已退款，真实退款未验证";
          break;
        default:
          meaning = "订单状态待确认";
      }
      row.put("statusMeaning", meaning);
    }
    if (source.get("pay_cents") != null) {
      row.put("priceMeaning", "payCents为券价分，不是实际付款或收款金额");
    }
    return row;
  }

  static Map<String, Object> claim(Object value) {
    Map<?, ?> source = (Map<?, ?>) value;
    String state = String.valueOf(source.get("state"));
    String meaning;
    switch (state) {
      case "PROCESSING":
        meaning = "请求已受理，尚未确认订单落库";
        break;
      case "SUCCESS":
        meaning = "订单已落库；不表示已付款";
        break;
      case "REVIEW":
        meaning = "请求等待核对，不能宣布领取成功";
        break;
      case "FAILED":
        meaning = "本次履约失败，不能宣布领取成功";
        break;
      default:
        throw new IllegalStateException("未知的领取请求状态");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    put(result, "orderId", source.get("order_id"), true);
    put(result, "voucherId", source.get("voucher_id"), true);
    result.put("state", state);
    result.put("meaning", meaning);
    result.put("paymentStatus", "NOT_CHECKED");
    return result;
  }

  private static void put(Map<String, Object> target, String key, Object value, boolean id) {
    if (value == null) {
      return;
    }
    if (id || value instanceof java.time.temporal.TemporalAccessor) {
      target.put(key, value.toString());
    } else if (value instanceof String) {
      String text = (String) value;
      target.put(key, text.substring(0, Math.min(1000, text.length())));
    } else {
      target.put(key, value);
    }
  }
}
