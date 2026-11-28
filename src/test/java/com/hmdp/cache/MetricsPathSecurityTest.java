package com.hmdp.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class MetricsPathSecurityTest {
  @Test
  void matrixParameterCannotSkipMonitoringAuthentication() throws Exception {
    MetricsAccessFilter filter = new MetricsAccessFilter(mock(StringRedisTemplate.class));
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator;x=1/prometheus");
    request.setServletPath("/actuator;x=1/prometheus");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    assertEquals(401, response.getStatus());
    assertNull(chain.getRequest());
  }
}
