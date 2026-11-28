package com.hmdp.knowledge;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative query-side lexical expansion; never creates evidence or changes source scope. */
public final class KnowledgeQueryTerms {
  private static final Set<String> QUESTION_WORDS =
      Collections.unmodifiableSet(
          new HashSet<>(Arrays.asList("请问", "什么", "怎么", "怎样", "一下", "这个", "那个", "需要")));
  private static final String[][] ALIASES = {
    {"付钱|交钱|支付", "付款"},
    {"没付钱|忘了付钱|没付款|没支付|未付款", "未支付"},
    {"打烊|关门|几点开|几点关", "营业时间"},
    {"车停|停哪|停哪里|停在|泊车", "停车"},
    {"订座|订位|预订", "预约"}
  };

  private KnowledgeQueryTerms() {}

  public static Map<String, Double> weights(String query) {
    Map<String, Double> weights = new LinkedHashMap<>();
    for (String term : KnowledgeText.tokens(query)) {
      if (!QUESTION_WORDS.contains(term)) {
        weights.put(term, 1.0);
      }
    }
    for (String[] alias : ALIASES) {
      if (Pattern.compile(alias[0]).matcher(query).find()) {
        expand(weights, alias[1]);
      }
    }
    if (query.contains("编号") && Pattern.compile("抢|秒杀|券|领取").matcher(query).find()) {
      expand(weights, "订单号");
    }
    if (Pattern.compile("忘|没|未").matcher(query).find()
        && Pattern.compile("付钱|付款|支付").matcher(query).find()
        && Pattern.compile("留着|保留").matcher(query).find()) {
      expand(weights, "超时");
    }
    return weights;
  }

  private static void expand(Map<String, Double> weights, String phrase) {
    for (String term : KnowledgeText.tokens(phrase)) {
      weights.putIfAbsent(term, 0.5);
    }
  }
}
