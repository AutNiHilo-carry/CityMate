package com.hmdp.reliability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hmdp.dto.UserDTO;
import com.hmdp.mapper.FavoriteMapper;
import com.hmdp.service.FavoriteService;
import com.hmdp.utils.UserHolder;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class FavoriteServiceTest {
  private final FavoriteMapper mapper = mock(FavoriteMapper.class);
  private final FavoriteService service = new FavoriteService(mapper);

  private void login(long id) {
    UserDTO user = new UserDTO();
    user.setId(id);
    UserHolder.saveUser(user);
  }

  @AfterEach
  void clear() {
    UserHolder.removeUser();
  }

  @Test
  void unauthenticatedCallsNeverTouchPrivateData() {
    assertEquals(
        401,
        assertThrows(ResponseStatusException.class, () -> service.state("shop", 1L))
            .getStatus()
            .value());
    assertThrows(ResponseStatusException.class, () -> service.list("shop", 1, 12));
    assertThrows(ResponseStatusException.class, () -> service.set("shop", 1L, true));
    verifyNoInteractions(mapper);
  }

  @Test
  void invalidKindsTargetsAndPaginationFailBeforeQuerying() {
    login(7);
    assertThrows(ResponseStatusException.class, () -> service.state("shop WHERE 1=1", 1L));
    assertThrows(ResponseStatusException.class, () -> service.state("shop", -1L));
    assertThrows(ResponseStatusException.class, () -> service.set("note", 1L, null));
    assertThrows(ResponseStatusException.class, () -> service.list("shop", 0, 12));
    assertThrows(ResponseStatusException.class, () -> service.list("shop", 1, 1000));
    verifyNoInteractions(mapper);
  }

  @Test
  void readsAlwaysUseCurrentAuthenticatedOwner() {
    login(7);
    when(mapper.countFavorite(7L, "shop", 99L)).thenReturn(1L);
    assertTrue(service.state("shop", 99L).getData());
    login(8);
    when(mapper.countFavorite(8L, "shop", 99L)).thenReturn(0L);
    assertFalse(service.state("shop", 99L).getData());
  }

  @Test
  void repeatedSaveUsesUniqueUpsertWithoutUpdatingSaveTime() {
    login(7);
    when(mapper.lockOwner(7L)).thenReturn(7L);
    when(mapper.countTarget("shop", 99L)).thenReturn(1L);
    assertTrue(service.set("shop", 99L, true).getData());
    assertTrue(service.set("shop", 99L, true).getData());
    verify(mapper, times(2)).save(7L, "shop", 99L);
  }

  @Test
  void repeatedListDoesNotChangeTheMapHeldByMybatisSessionCache() {
    login(7);
    Timestamp savedAt = Timestamp.valueOf("2026-11-28 11:00:00");
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", "99");
    row.put("savedAt", savedAt);
    when(mapper.countByOwner(7L, "shop")).thenReturn(1L);
    when(mapper.listByOwner(7L, "shop", 12, 0))
        .thenReturn(Collections.singletonList(Collections.unmodifiableMap(row)));
    Map<String, Object> first = service.list("shop", 1, 12).getData().get(0);
    Map<String, Object> second = service.list("shop", 1, 12).getData().get(0);
    assertEquals(savedAt.toInstant().toString(), first.get("savedAt"));
    assertEquals(first.get("savedAt"), second.get("savedAt"));
    assertEquals(savedAt, row.get("savedAt"));
    assertNotSame(row, first);
  }

  @Test
  void missingTargetCannotBeSavedButCanBeRemoved() {
    login(7);
    when(mapper.lockOwner(7L)).thenReturn(7L);
    when(mapper.countTarget("note", 99L)).thenReturn(0L);
    assertEquals(
        404,
        assertThrows(ResponseStatusException.class, () -> service.set("note", 99L, true))
            .getStatus()
            .value());
    assertFalse(service.set("note", 99L, false).getData());
    verify(mapper).remove(7L, "note", 99L);
  }
}
