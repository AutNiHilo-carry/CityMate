package com.hmdp.service;

import com.hmdp.dto.Result;
import java.util.List;
import java.util.Map;

public interface IFavoriteService {
  Result<Boolean> state(String kind, Long id);

  Result<Boolean> set(String kind, Long id, Boolean saved);

  Result<List<Map<String, Object>>> list(String kind, int page, int size);
}
