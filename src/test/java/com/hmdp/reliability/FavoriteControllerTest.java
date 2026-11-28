package com.hmdp.reliability;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.controller.FavoriteController;
import com.hmdp.dto.UserDTO;
import com.hmdp.mapper.FavoriteMapper;
import com.hmdp.service.FavoriteService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FavoriteControllerTest {
  private final FavoriteMapper mapper = mock(FavoriteMapper.class);
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new FavoriteController(new FavoriteService(mapper)))
          .setControllerAdvice(new WebExceptionAdvice())
          .build();

  @BeforeEach
  void login() {
    UserDTO user = new UserDTO();
    user.setId(7L);
    UserHolder.saveUser(user);
  }

  @AfterEach
  void clear() {
    UserHolder.removeUser();
  }

  @Test
  void invalidPaginationKeepsItsHttpStatusDespiteLegacyRuntimeAdvice() throws Exception {
    mvc.perform(get("/favorites").param("kind", "shop").param("page", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false));
  }

  @Test
  void unauthenticatedAndUnspecifiedStateHaveExplicitStatuses() throws Exception {
    mvc.perform(put("/favorites/shop/1").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
    UserHolder.removeUser();
    mvc.perform(get("/favorites/shop/1")).andExpect(status().isUnauthorized());
  }

  @Test
  void suppliedUserIdCannotReplaceAuthenticatedIdentity() throws Exception {
    when(mapper.lockOwner(7L)).thenReturn(7L);
    when(mapper.countTarget("shop", 1L)).thenReturn(1L);
    mvc.perform(
            put("/favorites/shop/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"saved\":true,\"userId\":8}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value(true));
    verify(mapper).save(7L, "shop", 1L);
  }
}
