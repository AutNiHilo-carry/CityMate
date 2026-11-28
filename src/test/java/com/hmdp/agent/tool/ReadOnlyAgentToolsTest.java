package com.hmdp.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hmdp.dto.AgentQueryRequest;
import com.hmdp.dto.QueryToolResult;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IKnowledgeService;
import com.hmdp.service.IQueryToolService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.UserHolder;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReadOnlyAgentToolsTest {
  private IQueryToolService queries;
  private IKnowledgeService knowledge;
  private IShopTypeService types;
  private IShopService shops;
  private ReadOnlyAgentTools tools;
  private final AgentQueryRequest scope = new AgentQueryRequest();

  @BeforeEach
  void setup() {
    queries = mock(IQueryToolService.class);
    knowledge = mock(IKnowledgeService.class);
    types = mock(IShopTypeService.class);
    shops = mock(IShopService.class);
    when(queries.supportedTools())
        .thenReturn(
            new HashSet<>(
                Arrays.asList(
                    "searchShops", "listShopVouchers", "listMyOrders", "getClaimStatus")));
    tools = new ReadOnlyAgentTools(queries, knowledge, types, shops);
    UserDTO user = new UserDTO();
    user.setId(7L);
    UserHolder.saveUser(user);
  }

  @AfterEach
  void clear() {
    tools.close();
    UserHolder.removeUser();
  }

  private Map<String, Object> execute(String name, Map<String, Object> args) {
    return tools.execute(name, args, scope, 2000);
  }

  @Test
  void schemaExposesExactlyTheSevenReadTools() {
    assertEquals(7, tools.definitions().size());
    for (Map<String, Object> item : tools.definitions()) {
      Map<?, ?> fn = (Map<?, ?>) item.get("function");
      assertTrue(tools.supports((String) fn.get("name")));
      assertEquals(false, ((Map<?, ?>) fn.get("parameters")).get("additionalProperties"));
    }
    assertFalse(tools.supports("claimVoucher"));
  }

  @Test
  void authenticatedIdentityIsPropagatedAndDoesNotLeakAcrossUsers() {
    when(queries.execute(eq("listMyOrders"), anyMap()))
        .thenAnswer(
            call ->
                QueryToolResult.ok(
                    "listMyOrders",
                    Collections.singletonMap("owner", UserHolder.getUser().getId()),
                    null));
    assertEquals(
        7L, ((Map<?, ?>) execute("listMyOrders", Collections.emptyMap()).get("data")).get("owner"));
    UserDTO other = new UserDTO();
    other.setId(8L);
    UserHolder.saveUser(other);
    assertEquals(
        8L, ((Map<?, ?>) execute("listMyOrders", Collections.emptyMap()).get("data")).get("owner"));
    assertEquals(8L, UserHolder.getUser().getId());
    UserHolder.removeUser();
    assertEquals("UNAUTHENTICATED", execute("listMyOrders", Collections.emptyMap()).get("status"));
  }

  @Test
  void inventedCoordinatesAndCrossShopVoucherRequestsAreRejected() {
    Map<String, Object> args = new HashMap<>();
    args.put("x", 120);
    args.put("y", 30);
    assertEquals("LOCATION_REQUIRED", execute("searchShops", args).get("status"));
    scope.setShopId(10L);
    assertEquals(
        "SCOPE_MISMATCH",
        execute("listShopVouchers", Collections.singletonMap("shopId", 11)).get("status"));
    verify(queries, never()).execute(anyString(), anyMap());
  }

  @Test
  void knowledgeRejectsIdentityArgumentsAndScopeEscape() {
    Map<String, Object> args = new HashMap<>();
    args.put("query", "规则");
    args.put("userId", 99);
    assertEquals("INVALID_ARGUMENT", execute("searchKnowledge", args).get("status"));
    args.remove("userId");
    args.put("shopId", 2);
    scope.setShopId(1L);
    assertEquals("INVALID_ARGUMENT", execute("searchKnowledge", args).get("status"));
    verify(knowledge, never()).search(any());
  }

  @Test
  void metadataComesFromActualCatalogAndPreservesLongIds() {
    ShopType row = new ShopType();
    row.setId(Long.MAX_VALUE);
    row.setName("火锅");
    when(types.queryTypeList()).thenReturn(Result.ok(Collections.singletonList(row)));
    Map<?, ?> data = (Map<?, ?>) execute("listShopTypes", Collections.emptyMap()).get("data");
    Map<?, ?> item = (Map<?, ?>) ((List<?>) data.get("items")).get(0);
    assertEquals(String.valueOf(Long.MAX_VALUE), item.get("typeId"));
    assertEquals("火锅", item.get("name"));
  }

  @Test
  void slowToolTimesOutWithoutBlockingCallerIndefinitely() {
    when(shops.listAreas())
        .thenAnswer(
            call -> {
              Thread.sleep(500);
              return Result.ok();
            });
    assertEquals(
        "TIMEOUT", tools.execute("listAreas", Collections.emptyMap(), scope, 10).get("status"));
  }

  @Test
  void sourceRevalidationUsesStoredKeyAndVersion() {
    when(knowledge.source("faq", 2)).thenReturn(Result.fail("withdrawn"));
    Map<String, Object> row = new HashMap<>();
    row.put("sourceKey", "faq");
    row.put("version", 2);
    assertFalse(tools.sourcesCurrent(Collections.singletonList(row), 1000));
    verify(knowledge).source("faq", 2);
  }

  @Test
  void platformKnowledgeWithoutShopOrVoucherKeepsBothScopesNull() {
    when(knowledge.search(any()))
        .thenAnswer(
            call -> {
              com.hmdp.dto.KnowledgeSearchRequest request = call.getArgument(0);
              assertEquals(null, request.getShopId());
              assertEquals(null, request.getVoucherId());
              return Result.ok(Collections.singletonMap("evidence", Collections.emptyList()));
            });
    assertEquals(
        "OK", execute("searchKnowledge", Collections.singletonMap("query", "平台规则")).get("status"));
    verify(knowledge).search(any());
  }
}
