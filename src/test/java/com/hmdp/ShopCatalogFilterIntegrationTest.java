package com.hmdp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hmdp.dto.Result;
import com.hmdp.dto.ShopSearchDTO;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
    "citymate.reliability.scheduling-enabled=false", "hmdp.cdc.enabled=false",
    "hmdp.mq.enabled=false", "hmdp.analytics.enabled=false"
})
@Transactional
class ShopCatalogFilterIntegrationTest {
  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private IShopService shops;

  @Autowired
  private SqlSessionTemplate sql;

  @BeforeEach
  void setup() {
    assertEquals("hmdp_optimization_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
    for (int i = 1; i <= 3; i++) {
      jdbc.update("INSERT INTO tb_shop(id,name,type_id,images,area,address,x,y,avg_price,"
          + "sold,comments,score,open_hours) SELECT ?,?,?,images,?,'fixture address',x,y,?,"
          + "sold,comments,?,open_hours FROM tb_shop WHERE id=1",
          948000L + i, "browse-filter-fixture " + (i == 1 ? "%_! A" : "B" + i),
          i == 3 ? 2 : 1, i == 3 ? "zone_B" : "zone_A%", i == 2 ? 150 : 50,
          i == 2 ? 42 : 48);
      jdbc.update("INSERT INTO tb_voucher(id,shop_id,title,rules,pay_value,actual_value,type,"
          + "status) VALUES(?,?,'fixture','rules',500,1000,?,1)",
          948010L + i, 948000L + i, i == 1 ? 0 : 1);
    }
    jdbc.update("INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) VALUES"
        + "(948012,10,DATE_SUB(NOW(),INTERVAL 2 DAY),DATE_SUB(NOW(),INTERVAL 1 DAY)),"
        + "(948013,5,DATE_SUB(NOW(),INTERVAL 1 DAY),DATE_ADD(NOW(),INTERVAL 1 DAY))");
  }

  private ShopSearchDTO criteria() {
    ShopSearchDTO value = new ShopSearchDTO();
    value.setKeyword("browse-filter-fixture");
    value.setSize(12);
    return value;
  }

  @SuppressWarnings("unchecked")
  private List<Shop> rows(Result result) {
    assertTrue(result.getSuccess());
    return (List<Shop>) result.getData();
  }

  @Test
  void categoryBudgetScoreAndLiteralAreaCombineAcrossTheCatalog() {
    ShopSearchDTO value = criteria();
    assertEquals(3L, shops.catalog(value).getTotal());
    value.setTypeId(1L);
    value.setMaxPrice(50L);
    value.setMinScore(45);
    value.setArea("zone_A%");
    List<Shop> result = rows(shops.catalog(value));
    assertEquals(1, result.size());
    assertEquals(948001L, result.get(0).getId());
  }

  @Test
  void voucherFilterExcludesExpiredAndEmptyActivitiesUsingCurrentDatabaseState() {
    ShopSearchDTO value = criteria();
    value.setHasVoucher(true);
    assertEquals(2, rows(shops.catalog(value)).size());
    jdbc.update("UPDATE tb_seckill_voucher SET stock=0 WHERE voucher_id=948013");
    // A new HTTP request has a new MyBatis session; the fixture JDBC update bypasses its cache.
    sql.clearCache();
    List<Shop> result = rows(shops.catalog(value));
    assertEquals(1, result.size());
    assertEquals(948001L, result.get(0).getId());
  }

  @Test
  void literalSearchAndPaginationDoNotTreatUserWildcardsAsSqlSyntax() {
    ShopSearchDTO value = criteria();
    value.setKeyword("%_!");
    assertEquals(948001L, rows(shops.catalog(value)).get(0).getId());
    value = criteria();
    value.setSize(1);
    long first = rows(shops.catalog(value)).get(0).getId();
    value.setPage(2);
    long second = rows(shops.catalog(value)).get(0).getId();
    assertTrue(first != second);
    assertEquals(3L, shops.catalog(value).getTotal());
  }
}
