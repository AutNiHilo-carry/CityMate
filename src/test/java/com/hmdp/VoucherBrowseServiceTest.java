package com.hmdp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherOffer;
import com.hmdp.mapper.VoucherBrowseMapper;
import com.hmdp.service.impl.VoucherBrowseService;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VoucherBrowseServiceTest {
  private final VoucherBrowseMapper mapper = mock(VoucherBrowseMapper.class);
  private final VoucherBrowseService service = new VoucherBrowseService(mapper);

  @Test
  void rejectsUnboundedOrInvalidQueriesBeforeDatabase() {
    assertFalse(service.search("", 0L, null, 1, 12).getSuccess());
    assertFalse(service.search("", null, -1L, 1, 12).getSuccess());
    assertFalse(service.search("", null, null, Integer.MAX_VALUE, 12).getSuccess());
    assertFalse(service.search("", null, null, 1, 21).getSuccess());
    assertFalse(service.shops(-1L).getSuccess());
    verifyNoInteractions(mapper);
  }

  @Test
  void preservesCombinedFiltersAndEscapesLiteralWildcards() {
    when(mapper.count(any(), any(), any())).thenReturn(19L);
    when(mapper.search(any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(Collections.emptyList());
    Result result = service.search("  50%_!  ", 2L, 5L, 2, 12);
    assertEquals(19L, result.getTotal());
    verify(mapper).search(eq("50!%!_!!"), eq(2L), eq(5L), eq(12), eq(12));
  }

  @Test
  void stockAndTimeWindowDetermineAvailabilityWithoutRemovingBrowseRows() {
    VoucherOffer ordinary = new VoucherOffer();
    ordinary.setType(0);
    VoucherOffer expired = new VoucherOffer();
    expired.setType(1).setStock(10).setBeginTime(LocalDateTime.now().minusDays(2))
        .setEndTime(LocalDateTime.now().minusDays(1));
    VoucherOffer empty = new VoucherOffer();
    empty.setType(1).setStock(0).setBeginTime(LocalDateTime.now().minusHours(1))
        .setEndTime(LocalDateTime.now().plusHours(1));
    when(mapper.count(any(), any(), any())).thenReturn(3L);
    when(mapper.search(any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(Arrays.asList(ordinary, expired, empty));
    assertTrue(service.search(null, null, null, 1, 12).getSuccess());
    assertTrue(ordinary.getOrderable());
    assertFalse(expired.getOrderable());
    assertFalse(empty.getOrderable());
  }

  @Test
  void previewQueriesTheExactVoucherWithoutDependingOnTheFirstTwentyRows() {
    VoucherOffer row = new VoucherOffer();
    row.setId(9007199254740993L).setShopId(5L).setType(0).setStatus(1)
        .setPayValue(500L).setActualValue(1000L).setTitle("voucher").setRules("rules");
    when(mapper.find(5L, 9007199254740993L)).thenReturn(row);
    Map<?, ?> data = (Map<?, ?>) service.preview(5L, 9007199254740993L).getData();
    assertEquals("9007199254740993", data.get("voucherId"));
    assertEquals("5", data.get("shopId"));
    assertEquals(500L, data.get("payCents"));
    assertEquals(true, data.get("availableAtQuery"));
    verify(mapper).find(5L, 9007199254740993L);
  }

  @Test
  void missingOrMismatchedVoucherCannotProduceAPreview() {
    assertFalse(service.preview(5L, 99L).getSuccess());
    assertFalse(service.preview(null, 99L).getSuccess());
    assertFalse(service.preview(5L, 0L).getSuccess());
  }
}
