package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.mapper.FavoriteMapper;
import com.hmdp.utils.UserHolder;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Private, idempotent collection facts. No cached user state or synthetic engagement events. */
@Service
public class FavoriteService implements IFavoriteService {
  private final FavoriteMapper mapper;

  public FavoriteService(FavoriteMapper mapper) {
    this.mapper = mapper;
  }

  private long owner() {
    if (UserHolder.getUser() == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
    }
    return UserHolder.getUser().getId();
  }

  private String table(String kind) {
    if ("shop".equals(kind)) {
      return "tb_shop";
    }
    if ("note".equals(kind)) {
      return "tb_blog";
    }
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收藏类型无效");
  }

  private void validate(String kind, Long id) {
    table(kind);
    if (id == null || id <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收藏对象无效");
    }
  }

  public Result<Boolean> state(String kind, Long id) {
    long user = owner();
    validate(kind, id);
    return Result.ok(mapper.countFavorite(user, kind, id) > 0);
  }

  @Transactional
  public Result<Boolean> set(String kind, Long id, Boolean saved) {
    long user = owner();
    validate(kind, id);
    if (saved == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请明确收藏或取消收藏");
    }
    // Serialize this owner's writes; the unique key remains the final duplicate guard.
    if (mapper.lockOwner(user) == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号已失效，请重新登录");
    }
    if (saved) {
      if (mapper.countTarget(kind, id) == 0) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "内容不存在或已下架");
      }
      mapper.save(user, kind, id);
    } else {
      // Deleted targets must still be removable from the private collection.
      mapper.remove(user, kind, id);
    }
    return Result.ok(saved);
  }

  @Transactional(readOnly = true)
  public Result<List<Map<String, Object>>> list(String kind, int page, int size) {
    long user = owner();
    table(kind);
    if (page < 1 || page > 10000 || size < 1 || size > 30) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页参数无效");
    }
    long total = mapper.countByOwner(user, kind);
    List<Map<String, Object>> items = new ArrayList<>();
    for (Map<String, Object> item : mapper.listByOwner(user, kind, size, (page - 1) * size)) {
      // MyBatis may reuse these maps within the transaction. Format only our response copy.
      Map<String, Object> response = new LinkedHashMap<>(item);
      response.put("savedAt", ((Timestamp) item.get("savedAt")).toInstant().toString());
      items.add(response);
    }
    return Result.ok(items, total);
  }
}
