package com.hmdp.service;

import com.hmdp.dto.Result;

public interface IVoucherBrowseService {
  Result search(String keyword, Long typeId, Long shopId, Integer page, Integer size);

  Result shops(Long typeId);

  Result preview(Long shopId, Long voucherId);
}
