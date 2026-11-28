package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherBrowseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/voucher")
public class VoucherBrowseController {
  private final IVoucherBrowseService browse;

  public VoucherBrowseController(IVoucherBrowseService browse) {
    this.browse = browse;
  }

  @GetMapping("/catalog")
  public Result catalog(@RequestParam(required = false) String keyword,
      @RequestParam(required = false) Long typeId, @RequestParam(required = false) Long shopId,
      @RequestParam(defaultValue = "1") Integer page,
      @RequestParam(defaultValue = "12") Integer size) {
    return browse.search(keyword, typeId, shopId, page, size);
  }

  @GetMapping("/shops")
  public Result shops(@RequestParam(required = false) Long typeId) {
    return browse.shops(typeId);
  }

  @GetMapping("/preview")
  public Result preview(@RequestParam Long shopId, @RequestParam Long voucherId) {
    return browse.preview(shopId, voucherId);
  }
}
