package com.hmdp.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 关键词检索基线：中文相邻双字、英文单词与数字，不冒充语义向量。 */
public final class KnowledgeText {
  private static final Pattern WORDS = Pattern.compile("[a-z0-9]+|[\\u4e00-\\u9fff]+");

  private KnowledgeText() {}

  public static List<String> chunks(String content, boolean voucherRules) {
    String normalized = content.replace("\r\n", "\n").replace('\r', '\n').trim();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("正文不能为空");
    }
    if (voucherRules) {
      if (normalized.length() > 1000) {
        throw new IllegalArgumentException("优惠券规则最多 1000 字，必须完整保存为一个片段");
      }
      return Collections.singletonList(normalized);
    }
    List<String> chunks = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String paragraph : normalized.split("\n\\s*\n")) {
      String remaining = paragraph.trim();
      if (remaining.isEmpty()) {
        continue;
      }
      if (current.length() > 0 && current.length() + remaining.length() + 2 > 800) {
        chunks.add(current.toString());
        current.setLength(0);
      }
      while (remaining.length() > 800) {
        int end = 800;
        if (Character.isHighSurrogate(remaining.charAt(end - 1))) {
          end--;
        }
        chunks.add(remaining.substring(0, end));
        remaining = remaining.substring(end);
      }
      if (current.length() > 0) {
        current.append("\n\n");
      }
      current.append(remaining);
    }
    if (current.length() > 0) {
      chunks.add(current.toString());
    }
    return chunks;
  }

  public static List<String> tokens(String text) {
    List<String> result = new ArrayList<>();
    Matcher matcher = WORDS.matcher(text.toLowerCase(Locale.ROOT));
    while (matcher.find()) {
      String word = matcher.group();
      if (word.charAt(0) >= '\u4e00' && word.charAt(0) <= '\u9fff' && word.length() > 1) {
        for (int index = 0; index < word.length() - 1; index++) {
          result.add(word.substring(index, index + 2));
        }
      } else {
        result.add(word);
      }
    }
    return result;
  }
}
