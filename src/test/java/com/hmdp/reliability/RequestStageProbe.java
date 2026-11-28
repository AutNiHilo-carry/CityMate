package com.hmdp.reliability;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.web.filter.OncePerRequestFilter;

/** Test-only synchronous client spans. Never stores tokens, keys, arguments or response bodies. */
@TestConfiguration
public class RequestStageProbe {
  private static final ThreadLocal<Trace> CURRENT = new ThreadLocal<>();
  private static final ConcurrentLinkedQueue<Trace> FINISHED = new ConcurrentLinkedQueue<>();

  @Bean
  public static BeanPostProcessor profileRedisCalls() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String name) {
        if (bean instanceof StringRedisTemplate) {
          ProxyFactory template = new ProxyFactory(bean);
          template.setProxyTargetClass(true);
          template.addAdvice(
              (org.aopalliance.intercept.MethodInterceptor)
                  invocation -> {
                    Object[] args = invocation.getArguments();
                    if (CURRENT.get() != null
                        && args.length > 0
                        && args[0] instanceof RedisScript) {
                      Object original = args[0];
                      args[0] =
                          Proxy.newProxyInstance(
                              RedisScript.class.getClassLoader(),
                              new Class<?>[] {RedisScript.class},
                              (proxy, method, values) -> {
                                String scriptMethod = method.getName();
                                if (!"getSha1".equals(scriptMethod)
                                    && !"getScriptAsString".equals(scriptMethod)) {
                                  return invoke(original, method, values);
                                }
                                long began = System.nanoTime();
                                try {
                                  return invoke(original, method, values);
                                } finally {
                                  CURRENT
                                      .get()
                                      .record(
                                          "getSha1".equals(scriptMethod)
                                              ? "scriptShaLookup"
                                              : "scriptBodyLookup",
                                          System.nanoTime() - began);
                                }
                              });
                    }
                    return invocation.proceed();
                  });
          return template.getProxy();
        }
        if (!(bean instanceof RedisConnectionFactory)) {
          return bean;
        }
        ProxyFactory proxy = new ProxyFactory(bean);
        proxy.addAdvice(
            (org.aopalliance.intercept.MethodInterceptor)
                invocation -> {
                  if (!"getConnection".equals(invocation.getMethod().getName())
                      || CURRENT.get() == null) {
                    return invocation.proceed();
                  }
                  long start = System.nanoTime();
                  Object connection;
                  try {
                    connection = invocation.proceed();
                  } finally {
                    CURRENT.get().record("connectionAcquire", System.nanoTime() - start);
                  }
                  return wrap(connection, invocation.getMethod().getReturnType());
                });
        return proxy.getProxy();
      }
    };
  }

  private static Object wrap(Object target, Class<?> api) {
    return Proxy.newProxyInstance(
        api.getClassLoader(),
        new Class<?>[] {api},
        (proxy, method, args) -> {
          Trace trace = CURRENT.get();
          Class<?> returns = method.getReturnType();
          if (returns.isInterface()
              && returns.getName().startsWith("org.springframework.data.redis.connection.")
              && returns.getSimpleName().endsWith("Commands")) {
            return wrap(invoke(target, method, args), returns);
          }
          if (trace == null || method.getDeclaringClass() == Object.class) {
            return invoke(target, method, args);
          }
          String stage = stage(method.getName(), args);
          long start = System.nanoTime();
          try {
            return invoke(target, method, args);
          } catch (Throwable failure) {
            if (scriptCacheMiss(failure)) {
              trace.scriptCacheMisses++;
            } else {
              trace.failures++;
            }
            throw failure;
          } finally {
            trace.record(stage, System.nanoTime() - start);
          }
        });
  }

  private static boolean scriptCacheMiss(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause.getClass().getSimpleName().contains("NoScript")
          || (cause.getMessage() != null && cause.getMessage().startsWith("NOSCRIPT"))) {
        return true;
      }
    }
    return false;
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  private static String stage(String command, Object[] args) {
    if ("hGetAll".equals(command)) {
      return "authRead";
    }
    if ("expire".equals(command) || "pExpire".equals(command)) {
      return "authRefresh";
    }
    if ("incr".equals(command)) {
      return "orderId";
    }
    if ("evalSha".equals(command) || "eval".equals(command)) {
      if (contains(args, "limit:claim-user:")) {
        return "userLimit";
      }
      if (contains(args, "limit:claim-global")) {
        return "globalLimit";
      }
      if (contains(args, "seckill:stock:")) {
        return "admissionLua";
      }
    }
    return "otherRedis";
  }

  private static boolean contains(Object value, String prefix) {
    if (value instanceof byte[]) {
      return new String((byte[]) value, StandardCharsets.UTF_8).startsWith(prefix);
    }
    if (value instanceof Object[]) {
      for (Object item : (Object[]) value) {
        if (contains(item, prefix)) {
          return true;
        }
      }
    }
    return false;
  }

  @Bean
  public FilterRegistrationBean<OncePerRequestFilter> profileRequestFilter() {
    FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>();
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    registration.setFilter(
        new OncePerRequestFilter() {
          @Override
          protected void doFilterInternal(
              HttpServletRequest request, HttpServletResponse response, FilterChain chain)
              throws ServletException, IOException {
            String path = request.getRequestURI();
            if (!"POST".equals(request.getMethod())
                || !path.matches("/voucher-order/seckill/[0-9]+")) {
              chain.doFilter(request, response);
              return;
            }
            Trace trace = new Trace(Long.parseLong(path.substring(path.lastIndexOf('/') + 1)));
            CURRENT.set(trace);
            long start = System.nanoTime();
            try {
              chain.doFilter(request, response);
            } finally {
              trace.totalNanos = System.nanoTime() - start;
              CURRENT.remove();
              FINISHED.add(trace);
            }
          }
        });
    return registration;
  }

  public static List<Map<String, Object>> take(long sku) {
    List<Map<String, Object>> samples = new ArrayList<>();
    for (Trace trace : FINISHED) {
      if (trace.sku == sku && FINISHED.remove(trace)) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("serverFilterMs", trace.totalNanos / 1_000_000.0);
        row.put("stages", trace.stages);
        row.put("redisFailures", trace.failures);
        row.put("scriptCacheMisses", trace.scriptCacheMisses);
        samples.add(row);
      }
    }
    return samples;
  }

  private static final class Trace {
    private final long sku;
    private final Map<String, Map<String, Object>> stages = new LinkedHashMap<>();
    private long totalNanos;
    private int failures;
    private int scriptCacheMisses;

    private Trace(long sku) {
      this.sku = sku;
    }

    private void record(String stage, long nanos) {
      Map<String, Object> data = stages.computeIfAbsent(stage, key -> new LinkedHashMap<>());
      data.put("calls", ((Number) data.getOrDefault("calls", 0)).intValue() + 1);
      data.put("nanos", ((Number) data.getOrDefault("nanos", 0L)).longValue() + nanos);
    }
  }
}
