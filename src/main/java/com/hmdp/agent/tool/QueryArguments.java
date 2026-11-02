package com.hmdp.agent.tool;

import com.hmdp.dto.ShopSearchDTO;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 严格解析模型参数；不做浮点 ID 截断、字符串布尔转换或任意字段绑定。 */
final class QueryArguments {
  private QueryArguments() {}

  static void only(Map<String, Object> arguments, String... names) {
    Set<String> allowed = new HashSet<>(Arrays.asList(names));
    for (String key : arguments.keySet()) {
      if (!allowed.contains(key)) {
        throw new IllegalArgumentException("包含不支持的参数");
      }
    }
  }

  static long integer(Map<String, Object> arguments, String name, long min, long max) {
    Object value = arguments.get(name);
    if (!(value instanceof String
        || value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long
        || value instanceof BigInteger)) {
      throw new IllegalArgumentException(name + " 必须是整数或十进制整数字符串");
    }
    String text = value.toString();
    if (text.length() > 19 || !text.matches("[0-9]+")) {
      throw new IllegalArgumentException(name + " 超出整数范围");
    }
    try {
      long parsed = Long.parseLong(text);
      if (parsed < min || parsed > max) {
        throw new NumberFormatException();
      }
      return parsed;
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException(name + " 超出允许范围");
    }
  }

  static int optionalInt(
      Map<String, Object> arguments, String name, int fallback, int min, int max) {
    return arguments.containsKey(name) ? (int) integer(arguments, name, min, max) : fallback;
  }

  static boolean flag(Map<String, Object> arguments, String name, boolean fallback) {
    if (!arguments.containsKey(name)) {
      return fallback;
    }
    if (!(arguments.get(name) instanceof Boolean)) {
      throw new IllegalArgumentException(name + " 必须是布尔值");
    }
    return (Boolean) arguments.get(name);
  }

  private static String text(Map<String, Object> arguments, String name, int maxLength) {
    if (!arguments.containsKey(name)) {
      return null;
    }
    Object value = arguments.get(name);
    if (!(value instanceof String) || ((String) value).length() > maxLength) {
      throw new IllegalArgumentException(name + " 必须是长度不超过 " + maxLength + " 的字符串");
    }
    return ((String) value).trim();
  }

  private static Double coordinate(
      Map<String, Object> arguments, String name, double min, double max) {
    if (!arguments.containsKey(name)) {
      return null;
    }
    Object value = arguments.get(name);
    if (!(value instanceof Number || value instanceof String) || value.toString().length() > 32) {
      throw new IllegalArgumentException(name + " 必须是有效坐标");
    }
    try {
      double parsed = Double.parseDouble(value.toString());
      if (!Double.isFinite(parsed) || parsed < min || parsed > max) {
        throw new NumberFormatException();
      }
      return parsed;
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException(name + " 超出坐标范围");
    }
  }

  static ShopSearchDTO search(Map<String, Object> arguments) {
    only(
        arguments,
        "typeId",
        "keyword",
        "area",
        "maxPrice",
        "minScore",
        "hasVoucher",
        "x",
        "y",
        "page",
        "size",
        "sort");
    ShopSearchDTO criteria = new ShopSearchDTO();
    criteria.setTypeId(integer(arguments, "typeId", 1, Long.MAX_VALUE));
    criteria.setKeyword(text(arguments, "keyword", 50));
    criteria.setArea(text(arguments, "area", 80));
    if (arguments.containsKey("maxPrice")) {
      criteria.setMaxPrice(integer(arguments, "maxPrice", 0, 10000000));
    }
    if (arguments.containsKey("minScore")) {
      criteria.setMinScore((int) integer(arguments, "minScore", 0, 50));
    }
    criteria.setHasVoucher(flag(arguments, "hasVoucher", false));
    criteria.setPage(optionalInt(arguments, "page", 1, 1, 10000));
    criteria.setSize(optionalInt(arguments, "size", 10, 1, 20));
    criteria.setX(coordinate(arguments, "x", -180, 180));
    criteria.setY(coordinate(arguments, "y", -90, 90));
    String sort = text(arguments, "sort", 8);
    criteria.setSort(sort == null ? "score" : sort);
    if (!Arrays.asList("score", "price", "distance").contains(criteria.getSort())) {
      throw new IllegalArgumentException("sort 只支持 score、price、distance");
    }
    if ((criteria.getX() == null) != (criteria.getY() == null)
        || ("distance".equals(criteria.getSort()) && criteria.getX() == null)) {
      throw new IllegalArgumentException("附近查询需要同时提供真实的 x、y 坐标");
    }
    return criteria;
  }
}
