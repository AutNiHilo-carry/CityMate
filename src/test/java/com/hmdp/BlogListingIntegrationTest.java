package com.hmdp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.UserHolder;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
    "citymate.reliability.scheduling-enabled=false", "hmdp.cdc.enabled=false",
    "hmdp.mq.enabled=false", "hmdp.analytics.enabled=false"
})
@Transactional
class BlogListingIntegrationTest {
  private static final long SHOP = 946001L;
  private static final long AUTHOR = 946011L;
  private static final long VIEWER = 946012L;

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private IBlogService blogs;

  @BeforeEach
  void setup() {
    assertEquals("hmdp_optimization_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
    jdbc.update("INSERT INTO tb_shop(id,name,type_id,images,area,address,x,y,avg_price,"
        + "sold,comments,score,open_hours) SELECT ?,name,type_id,images,area,address,x,y,"
        + "avg_price,sold,comments,score,open_hours FROM tb_shop WHERE id=1", SHOP);
    jdbc.update("INSERT INTO tb_user(id,phone,nick_name,icon) VALUES"
        + "(?,'13909460111','browse-author','/imgs/icons/default-icon.png'),"
        + "(?,'13909460112','browse-viewer','')", AUTHOR, VIEWER);
    for (int i = 1; i <= 6; i++) {
      jdbc.update("INSERT INTO tb_blog(id,shop_id,user_id,title,images,content,liked,"
          + "create_time) VALUES(?,?,?,'browse-note','','content',?, '2026-11-11 12:00:00')",
          946100L + i, SHOP, AUTHOR, i == 6 ? 1 : 0);
    }
    jdbc.update("INSERT INTO tb_blog_like(blog_id,user_id) VALUES(946106,?)", VIEWER);
    identify(VIEWER);
  }

  @AfterEach
  void clearIdentity() {
    UserHolder.removeUser();
  }

  private void identify(long id) {
    UserDTO user = new UserDTO();
    user.setId(id);
    UserHolder.saveUser(user);
  }

  @SuppressWarnings("unchecked")
  private List<Blog> rows(Result result) {
    assertTrue(result.getSuccess());
    return (List<Blog>) result.getData();
  }

  @Test
  void shopPagesHaveStableOrderAuthorDetailsAndViewerLikeState() {
    Result result = blogs.queryBlogByShop(SHOP, 1);
    List<Blog> first = rows(result);
    assertEquals(6L, result.getTotal());
    assertEquals(5, first.size());
    assertEquals(946106L, first.get(0).getId());
    assertEquals("browse-author", first.get(0).getName());
    assertTrue(first.get(0).getIsLike());
    List<Blog> second = rows(blogs.queryBlogByShop(SHOP, 2));
    assertEquals(1, second.size());
    assertEquals(946101L, second.get(0).getId());
    assertFalse(second.get(0).getIsLike());
  }

  @Test
  void profileListingsAlsoCarryAuthorAndCurrentViewerState() {
    List<Blog> other = rows(blogs.queryBlogByUserId(1, AUTHOR));
    assertEquals(6, other.size());
    assertTrue(other.get(0).getIsLike());
    identify(AUTHOR);
    List<Blog> own = rows(blogs.queryMyBlog(1));
    assertEquals("browse-author", own.get(0).getName());
    assertFalse(own.get(0).getIsLike());
  }

  @Test
  void guestListingsHaveNoLikeStateAndInvalidPagesAreRejected() {
    UserHolder.removeUser();
    assertFalse(rows(blogs.queryBlogByShop(SHOP, 1)).get(0).getIsLike());
    assertFalse(blogs.queryBlogByShop(SHOP, null).getSuccess());
    assertFalse(blogs.queryBlogByUserId(0, AUTHOR).getSuccess());
    assertFalse(blogs.queryMyBlog(0).getSuccess());
  }
}
