package com.hmdp.controller;

import com.hmdp.dto.QueryToolResult;
import com.hmdp.dto.Result;
import com.hmdp.service.IQueryToolService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Fresh, authenticated preview only. Submission uses the existing transaction endpoints. */
@RestController
public class AssistantClaimController {
  private final IQueryToolService queries;

  public AssistantClaimController(IQueryToolService queries) {
    this.queries = queries;
  }

  @GetMapping("/assistant/claim-preview")
  public Result<?> preview(@RequestParam Long shopId, @RequestParam Long voucherId) {
    if (shopId == null || shopId <= 0 || voucherId == null || voucherId <= 0) {
      return Result.fail("店铺或优惠券编号无效");
    }
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("shopId", shopId);
    arguments.put("limit", 20);
    QueryToolResult result = queries.execute("listShopVouchers", arguments);
    if (result.getStatus() != QueryToolResult.Status.OK) {
      return Result.fail("暂时无法核对优惠券，请稍后重试");
    }
    Object items = result.getData().get("items");
    if (items instanceof List) {
      for (Object item : (List<?>) items) {
        if (!(item instanceof Map)) {
          continue;
        }
        Map<?, ?> row = (Map<?, ?>) item;
        if (voucherId.toString().equals(row.get("voucherId"))
            && shopId.toString().equals(row.get("shopId"))) {
          Map<String, Object> preview = new LinkedHashMap<>();
          for (String field :
              new String[] {
                "shopId",
                "voucherId",
                "title",
                "rules",
                "type",
                "payCents",
                "valueCents",
                "stock",
                "beginTime",
                "endTime",
                "availableAtQuery"
              }) {
            if (row.containsKey(field)) {
              preview.put(field, row.get(field));
            }
          }
          preview.put("observedAt", result.getObservedAt());
          return Result.ok(preview);
        }
      }
    }
    return Result.fail("优惠券已变化、已下架或不在当前可核对列表，请重新查询");
  }
}
