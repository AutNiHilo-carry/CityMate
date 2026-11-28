package com.hmdp.reliability;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hmdp.controller.ShopController;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.service.IOperationAuditService;
import com.hmdp.service.IShopService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AccessGuardRoutingTest {
  @AfterEach
  void clear() {
    UserHolder.removeUser();
  }

  private MockMvc mvc(IShopService shops) {
    ShopController controller = new ShopController();
    ReflectionTestUtils.setField(controller, "shopService", shops);
    AccessGuard guard = new AccessGuard(mock(RateLimiter.class));
    ReflectionTestUtils.setField(guard, "admins", "1010");
    ReflectionTestUtils.setField(
        guard, "operationAuditService", mock(IOperationAuditService.class));
    return MockMvcBuilders.standaloneSetup(controller).addInterceptors(guard).build();
  }

  @Test
  void matrixParameterCannotTurnAdminShopUpdateIntoOrdinaryUserWrite() throws Exception {
    UserDTO user = new UserDTO();
    user.setId(99L);
    UserHolder.saveUser(user);
    IShopService shops = mock(IShopService.class);
    when(shops.update(any(Shop.class))).thenReturn(Result.ok());
    mvc(shops)
        .perform(put("/shop;v=1").contentType(MediaType.APPLICATION_JSON).content("{\"id\":1}"))
        .andExpect(status().isForbidden());
    verify(shops, never()).update(any(Shop.class));
  }

  @Test
  void adminCanStillUseTheMappedShopUpdate() throws Exception {
    UserDTO user = new UserDTO();
    user.setId(1010L);
    UserHolder.saveUser(user);
    IShopService shops = mock(IShopService.class);
    when(shops.update(any(Shop.class))).thenReturn(Result.ok());
    mvc(shops)
        .perform(put("/shop;v=1").contentType(MediaType.APPLICATION_JSON).content("{\"id\":1}"))
        .andExpect(status().isOk());
    verify(shops).update(any(Shop.class));
  }
}
