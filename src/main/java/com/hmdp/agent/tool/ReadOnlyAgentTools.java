package com.hmdp.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.AgentQueryRequest;
import com.hmdp.dto.KnowledgeSearchRequest;
import com.hmdp.dto.QueryToolResult;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IKnowledgeService;
import com.hmdp.service.IQueryToolService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.UserHolder;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import javax.annotation.PreDestroy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Only explicit read methods, on bounded workers with server-side identity propagation. */
@Component
public class ReadOnlyAgentTools {
  private final IQueryToolService queries;
  private final IKnowledgeService knowledge;
  private final IShopTypeService types;
  private final IShopService shops;
  private final ObjectMapper json = new ObjectMapper();
  private final List<Map<String, Object>> definitions;
  private final ThreadPoolExecutor workers =
      new ThreadPoolExecutor(
          2,
          2,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(4),
          task -> {
            Thread thread = new Thread(task, "citymate-agent-read");
            thread.setDaemon(true);
            return thread;
          },
          new ThreadPoolExecutor.AbortPolicy());

  public ReadOnlyAgentTools(
      IQueryToolService queries,
      IKnowledgeService knowledge,
      IShopTypeService types,
      IShopService shops) {
    this.queries = queries;
    this.knowledge = knowledge;
    this.types = types;
    this.shops = shops;
    try (InputStream input = new ClassPathResource("agent-readonly-tools.json").getInputStream()) {
      definitions = json.readValue(input, new TypeReference<List<Map<String, Object>>>() {});
    } catch (Exception failure) {
      throw new IllegalStateException("Read-only agent tool definitions unavailable", failure);
    }
  }

  public List<Map<String, Object>> definitions() {
    return definitions;
  }

  public boolean supports(String name) {
    return queries.supportedTools().contains(name)
        || "searchKnowledge".equals(name)
        || "listShopTypes".equals(name)
        || "listAreas".equals(name);
  }

  public Map<String, Object> execute(
      String name, Map<String, Object> args, AgentQueryRequest scope, long budgetMillis) {
    return bounded(() -> invoke(name, args, scope), budgetMillis);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> invoke(
      String name, Map<String, Object> args, AgentQueryRequest scope) {
    try {
      if (!supports(name)) {
        return error("INVALID_ARGUMENT");
      }
      if ("searchShops".equals(name) && (args.containsKey("x") || args.containsKey("y"))) {
        if (scope.getX() == null
            || scope.getY() == null
            || !sameCoordinate(args.get("x"), scope.getX())
            || !sameCoordinate(args.get("y"), scope.getY())) {
          return error("LOCATION_REQUIRED");
        }
      }
      if ("listShopVouchers".equals(name)
          && scope.getShopId() != null
          && QueryArguments.integer(args, "shopId", 1, Long.MAX_VALUE) != scope.getShopId()) {
        return error("SCOPE_MISMATCH");
      }
      if (queries.supportedTools().contains(name)) {
        QueryToolResult value = queries.execute(name, args);
        Map<String, Object> result =
            json.convertValue(value, new TypeReference<Map<String, Object>>() {});
        // QueryToolResult is already an output-field whitelist.
        return result;
      }
      if ("searchKnowledge".equals(name)) {
        QueryArguments.only(args, "query", "shopId", "voucherId", "limit");
        if (!(args.get("query") instanceof String)) {
          return error("INVALID_ARGUMENT");
        }
        KnowledgeSearchRequest request = new KnowledgeSearchRequest();
        request.setQuery((String) args.get("query"));
        request.setLimit(QueryArguments.optionalInt(args, "limit", 3, 1, 3));
        request.setShopId(scopedId(args, "shopId", scope.getShopId()));
        request.setVoucherId(scopedId(args, "voucherId", scope.getVoucherId()));
        Result<Map<String, Object>> found = knowledge.search(request);
        if (!Boolean.TRUE.equals(found.getSuccess())) {
          return error("BUSINESS_REJECTED");
        }
        return ok(found.getData());
      }
      QueryArguments.only(args);
      if ("listShopTypes".equals(name)) {
        Result<?> result = types.queryTypeList();
        if (!Boolean.TRUE.equals(result.getSuccess()) || !(result.getData() instanceof List)) {
          return error("UNAVAILABLE");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object value : (List<?>) result.getData()) {
          if (rows.size() >= 50) {
            break;
          }
          ShopType type = (ShopType) value;
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("typeId", String.valueOf(type.getId()));
          row.put("name", type.getName());
          rows.add(row);
        }
        return ok(Collections.singletonMap("items", rows));
      }
      Result<?> areas = shops.listAreas();
      if (!Boolean.TRUE.equals(areas.getSuccess()) || !(areas.getData() instanceof List)) {
        return error("UNAVAILABLE");
      }
      List<?> values = (List<?>) areas.getData();
      return ok(Collections.singletonMap("items", values.subList(0, Math.min(50, values.size()))));
    } catch (IllegalArgumentException invalid) {
      return error("INVALID_ARGUMENT");
    }
  }

  private static boolean sameCoordinate(Object value, double expected) {
    try {
      return value != null && Math.abs(Double.parseDouble(value.toString()) - expected) < 0.000001;
    } catch (NumberFormatException invalid) {
      return false;
    }
  }

  private static Long scopedId(Map<String, Object> args, String name, Long scope) {
    Long id =
        args.containsKey(name)
            ? Long.valueOf(QueryArguments.integer(args, name, 1, Long.MAX_VALUE))
            : scope;
    if (scope != null && !scope.equals(id)) {
      throw new IllegalArgumentException("Scope mismatch");
    }
    return id;
  }

  public boolean sourcesCurrent(List<Map<String, Object>> sources, long budgetMillis) {
    Map<String, Object> result =
        bounded(
            () -> {
              for (Map<String, Object> source : sources) {
                if (!Boolean.TRUE.equals(
                    knowledge
                        .source(
                            (String) source.get("sourceKey"),
                            ((Number) source.get("version")).intValue())
                        .getSuccess())) {
                  return error("STALE_EVIDENCE");
                }
              }
              return ok(Collections.emptyMap());
            },
            budgetMillis);
    return "OK".equals(result.get("status"));
  }

  private Map<String, Object> bounded(Supplier<Map<String, Object>> work, long budgetMillis) {
    if (budgetMillis <= 0) {
      return error("TIMEOUT");
    }
    UserDTO current = UserHolder.getUser();
    if (current == null || current.getId() == null) {
      return error("UNAUTHENTICATED");
    }
    final long userId = current.getId();
    Future<Map<String, Object>> task = null;
    try {
      task =
          workers.submit(
              () -> {
                UserHolder.removeUser();
                try {
                  UserDTO identity = new UserDTO();
                  identity.setId(userId);
                  UserHolder.saveUser(identity);
                  return work.get();
                } finally {
                  UserHolder.removeUser();
                }
              });
      return task.get(Math.min(3000, budgetMillis), TimeUnit.MILLISECONDS);
    } catch (TimeoutException timeout) {
      return error("TIMEOUT");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return error("UNAVAILABLE");
    } catch (Exception failure) {
      return error("UNAVAILABLE");
    } finally {
      if (task != null && !task.isDone()) {
        task.cancel(true);
        workers.remove((Runnable) task);
      }
    }
  }

  private static Map<String, Object> ok(Object data) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("status", "OK");
    result.put("data", data);
    return result;
  }

  private static Map<String, Object> error(String status) {
    return Collections.singletonMap("status", status);
  }

  @PreDestroy
  public void close() {
    workers.shutdownNow();
  }
}
