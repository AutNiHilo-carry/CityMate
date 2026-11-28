package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherOffer;
import com.hmdp.mapper.VoucherBrowseMapper;
import com.hmdp.service.IVoucherBrowseService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Bounded database-side browsing, independent of claim execution. */
@Service
public class VoucherBrowseService implements IVoucherBrowseService {
  private final VoucherBrowseMapper mapper;

  public VoucherBrowseService(VoucherBrowseMapper mapper) {
    this.mapper = mapper;
  }

  public Result search(String keyword, Long typeId, Long shopId, Integer page, Integer size) {
    String text = keyword == null ? "" : keyword.trim();
    if (text.length() > 100 || invalidId(typeId) || invalidId(shopId)
        || page == null || page < 1 || page > 10000
        || size == null || size < 1 || size > 20) {
      return Result.fail("搜索条件无效，关键词最多100字，每页最多20条");
    }
    String escaped = text.isEmpty() ? null
        : text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    Long total = mapper.count(escaped, typeId, shopId);
    List<VoucherOffer> rows = mapper.search(escaped, typeId, shopId, size, (page - 1) * size);
    for (VoucherOffer row : rows) {
      setAvailability(row);
    }
    return Result.ok(rows, total);
  }

  public Result shops(Long typeId) {
    if (invalidId(typeId)) {
      return Result.fail("店铺分类无效");
    }
    List<Map<String, Object>> rows = mapper.shops(typeId);
    Map<String, Object> data = new HashMap<>();
    data.put("hasMore", rows.size() > 200);
    data.put("shops", rows.subList(0, Math.min(200, rows.size())));
    return Result.ok(data);
  }

  private boolean invalidId(Long id) {
    return id != null && id <= 0;
  }

  @Override
  public Result preview(Long shopId, Long voucherId) {
    if (shopId == null || voucherId == null || invalidId(shopId) || invalidId(voucherId)) {
      return Result.fail("店铺或优惠券编号无效");
    }
    VoucherOffer row = mapper.find(shopId, voucherId);
    if (row == null) {
      return Result.fail("优惠券已变化或已下架，请重新查询");
    }
    setAvailability(row);
    Map<String, Object> data = new HashMap<>();
    data.put("shopId", row.getShopId().toString());
    data.put("voucherId", row.getId().toString());
    data.put("title", row.getTitle());
    data.put("rules", row.getRules());
    data.put("type", row.getType());
    data.put("payCents", row.getPayValue());
    data.put("valueCents", row.getActualValue());
    data.put("beginTime", row.getBeginTime() == null ? null : row.getBeginTime().toString());
    data.put("endTime", row.getEndTime() == null ? null : row.getEndTime().toString());
    data.put("availableAtQuery", row.getOrderable());
    data.put("observedAt", Instant.now().toString());
    return Result.ok(data);
  }

  private void setAvailability(VoucherOffer row) {
    LocalDateTime now = LocalDateTime.now();
    row.setOrderable(Objects.equals(row.getType(), 0)
        || (Objects.equals(row.getType(), 1) && row.getStock() != null && row.getStock() > 0
            && row.getBeginTime() != null && row.getEndTime() != null
            && !now.isBefore(row.getBeginTime()) && !now.isAfter(row.getEndTime())));
  }
}
