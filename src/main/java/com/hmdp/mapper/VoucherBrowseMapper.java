package com.hmdp.mapper;

import com.hmdp.dto.VoucherOffer;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface VoucherBrowseMapper {
  List<VoucherOffer> search(@Param("keyword") String keyword, @Param("typeId") Long typeId,
      @Param("shopId") Long shopId, @Param("size") Integer size, @Param("offset") Integer offset);

  Long count(@Param("keyword") String keyword, @Param("typeId") Long typeId,
      @Param("shopId") Long shopId);

  List<Map<String, Object>> shops(@Param("typeId") Long typeId);

  VoucherOffer find(@Param("shopId") Long shopId, @Param("voucherId") Long voucherId);
}
