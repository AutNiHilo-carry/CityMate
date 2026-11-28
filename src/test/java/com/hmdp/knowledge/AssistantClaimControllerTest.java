package com.hmdp.knowledge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hmdp.controller.AssistantClaimController;
import com.hmdp.dto.QueryToolResult;
import com.hmdp.service.IQueryToolService;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AssistantClaimControllerTest {
  @Test
  void invalidIdsCannotDispatchAnyTool() {
    IQueryToolService tools = mock(IQueryToolService.class);
    assertFalse(new AssistantClaimController(tools).preview(0L, 1L).getSuccess());
    verifyNoInteractions(tools);
  }

  @Test
  void differentShopCannotSupplyThePreviewAndNoWriteToolIsUsed() {
    IQueryToolService tools = mock(IQueryToolService.class);
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("voucherId", "12");
    row.put("shopId", "2");
    when(tools.execute(eq("listShopVouchers"), anyMap()))
        .thenReturn(
            QueryToolResult.ok(
                "listShopVouchers",
                Collections.singletonMap("items", Collections.singletonList(row)),
                null));
    assertFalse(new AssistantClaimController(tools).preview(1L, 12L).getSuccess());
  }

  @Test
  void previewReturnsOnlyWhitelistedServerFields() {
    IQueryToolService tools = mock(IQueryToolService.class);
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("voucherId", "12");
    row.put("shopId", "1");
    row.put("internalNote", "private");
    when(tools.execute(eq("listShopVouchers"), anyMap()))
        .thenReturn(
            QueryToolResult.ok(
                "listShopVouchers",
                Collections.singletonMap("items", Collections.singletonList(row)),
                null));
    Map<?, ?> data = (Map<?, ?>) new AssistantClaimController(tools).preview(1L, 12L).getData();
    assertTrue(data.containsKey("observedAt"));
    assertFalse(data.containsKey("internalNote"));
  }
}
