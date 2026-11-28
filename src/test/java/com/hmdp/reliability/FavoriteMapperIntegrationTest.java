package com.hmdp.reliability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hmdp.dto.UserDTO;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IFavoriteService;
import com.hmdp.utils.UserHolder;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    properties = {
      "citymate.reliability.scheduling-enabled=false",
      "hmdp.cdc.enabled=false",
      "hmdp.mq.enabled=false",
      "hmdp.analytics.enabled=false"
    })
@Transactional
class FavoriteMapperIntegrationTest {
  @Autowired private JdbcTemplate jdbc;
  @Autowired private IFavoriteService favorites;
  @Autowired private BlogMapper blogs;

  @BeforeEach
  void fixture() {
    assertEquals("hmdp_optimization_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
    jdbc.update(
        "INSERT INTO tb_user(id,phone,nick_name) VALUES(949001,'13900094901','favorite fixture'),"
            + "(949002,'13900094902','other favorite fixture')");
    jdbc.update(
        "INSERT INTO tb_shop(id,name,type_id,images,area,address,x,y,avg_price,"
            + "sold,comments,score,open_hours) SELECT 949001,'favorite fixture',type_id,"
            + "images,area,address,x,y,avg_price,sold,comments,score,open_hours"
            + " FROM tb_shop WHERE id=1");
    jdbc.update(
        "INSERT INTO tb_blog(id,shop_id,user_id,title,images,content)"
            + " VALUES(949001,949001,949001,'favorite note','fixture.jpg','fixture')");
    login(949001);
  }

  private void login(long id) {
    UserDTO user = new UserDTO();
    user.setId(id);
    UserHolder.saveUser(user);
  }

  @AfterEach
  void clearIdentity() {
    UserHolder.removeUser();
  }

  @Test
  void repeatSavePreservesOneFactAndReturnsTheExistingPublicShape() {
    assertTrue(favorites.set("shop", 949001L, true).getData());
    Map<String, Object> before = favorites.list("shop", 1, 12).getData().get(0);
    assertTrue(favorites.set("shop", 949001L, true).getData());
    assertEquals(1L, favorites.list("shop", 1, 12).getTotal());
    Map<String, Object> after = favorites.list("shop", 1, 12).getData().get(0);
    assertEquals("949001", after.get("id"));
    assertEquals("shop", after.get("kind"));
    assertEquals(Boolean.TRUE, after.get("available"));
    assertEquals("favorite fixture", after.get("title"));
    assertEquals(before.get("savedAt"), after.get("savedAt"));
    assertTrue(after.get("savedAt") instanceof String);
  }

  @Test
  void authenticatedOwnersHaveIndependentReadsAndRemoval() {
    favorites.set("shop", 949001L, true);
    login(949002);
    assertFalse(favorites.state("shop", 949001L).getData());
    assertEquals(0L, favorites.list("shop", 1, 12).getTotal());
    favorites.set("shop", 949001L, false);
    login(949001);
    assertTrue(favorites.state("shop", 949001L).getData());
  }

  @Test
  void removedNoteRemainsVisibleAsUnavailableAndCanBeUnsaved() {
    favorites.set("note", 949001L, true);
    assertEquals("favorite note", favorites.list("note", 1, 12).getData().get(0).get("title"));
    assertEquals(1, blogs.deleteById(949001L));
    assertEquals(Boolean.FALSE, favorites.list("note", 1, 12).getData().get(0).get("available"));
    assertFalse(favorites.set("note", 949001L, false).getData());
    assertEquals(0L, favorites.list("note", 1, 12).getTotal());
  }
}
