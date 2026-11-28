package com.hmdp.dto;

import com.hmdp.entity.Voucher;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Public voucher browse row with its owning shop. */
@Data
@EqualsAndHashCode(callSuper = true)
public class VoucherOffer extends Voucher {
  private String shopName;
}
