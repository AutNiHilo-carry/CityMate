package com.hmdp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hmdp.dto.Result;
import com.hmdp.dto.ShopSearchDTO;
import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;

class ShopSearchBoundaryTest {
  private final ShopServiceImpl service = new ShopServiceImpl();

  private ShopSearchDTO valid() {
    ShopSearchDTO criteria = new ShopSearchDTO();
    criteria.setTypeId(1L);
    criteria.setPage(1);
    criteria.setSize(10);
    return criteria;
  }

  @Test
  void legacyNearbyRouteRejectsInvalidPagesAndCoordinatesBeforeRedisOrDatabase() {
    assertFalse(service.queryShopByType(1, 0, null, null).getSuccess());
    assertFalse(service.queryShopByType(1, Integer.MAX_VALUE, 120.0, 30.0).getSuccess());
    assertFalse(service.queryShopByType(null, 1, null, null).getSuccess());
    assertFalse(service.queryShopByType(1, 1, 120.0, null).getSuccess());
    assertFalse(service.queryShopByType(1, 1, Double.NaN, 30.0).getSuccess());
    assertFalse(service.queryShopByType(1, 1, 120.0, 86.0).getSuccess());
  }

  @Test
  void legacyNameRouteRejectsUnboundedInputBeforeDatabaseAccess() {
    assertFalse(service.queryShopByName("shop", null).getSuccess());
    assertFalse(service.queryShopByName("shop", Integer.MAX_VALUE).getSuccess());
    assertFalse(
        service
            .queryShopByName(String.join("", java.util.Collections.nCopies(101, "x")), 1)
            .getSuccess());
  }

  @Test
  void rejectsInvalidSearchBeforeDatabaseAccess() {
    ShopSearchDTO criteria = valid();
    criteria.setSize(21);
    assertFalse(service.searchShops(criteria).getSuccess());

    criteria = valid();
    criteria.setMinScore(51);
    assertFalse(service.searchShops(criteria).getSuccess());

    criteria = valid();
    criteria.setX(120.0);
    assertFalse(service.searchShops(criteria).getSuccess());

    criteria = valid();
    criteria.setSort("distance");
    Result result = service.searchShops(criteria);
    assertFalse(result.getSuccess());
    assertTrue(result.getErrorMsg().contains("x 和 y"));
  }

  @Test
  void catalogRejectsInvalidAdvancedCriteriaBeforeDatabaseAccess() {
    assertFalse(service.catalog((ShopSearchDTO) null).getSuccess());
    assertFalse(service.catalog("", null, null, 12, "score").getSuccess());
    ShopSearchDTO criteria = valid();
    criteria.setMaxPrice(-1L);
    assertFalse(service.catalog(criteria).getSuccess());
    criteria = valid();
    criteria.setMinScore(51);
    assertFalse(service.catalog(criteria).getSuccess());
    criteria = valid();
    criteria.setArea(String.join("", java.util.Collections.nCopies(51, "x")));
    assertFalse(service.catalog(criteria).getSuccess());
  }
}
