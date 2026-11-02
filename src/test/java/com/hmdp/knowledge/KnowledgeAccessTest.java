package com.hmdp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hmdp.dto.UserDTO;
import com.hmdp.reliability.AccessGuard;
import com.hmdp.reliability.RateLimiter;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

class KnowledgeAccessTest {
  private final RateLimiter limiter = mock(RateLimiter.class);
  private final AccessGuard guard = new AccessGuard(limiter);

  @AfterEach
  void clear() {
    UserHolder.removeUser();
  }

  @Test
  void publicReadIsLimitedAndDoesNotGrantPublicationRights() throws Exception {
    ReflectionTestUtils.setField(guard, "admins", "");
    when(limiter.allow(anyString(), anyInt(), anyLong())).thenReturn(true);
    assertTrue(
        guard.preHandle(
            new MockHttpServletRequest("GET", "/knowledge/search"),
            new MockHttpServletResponse(),
            new Object()));
    MockHttpServletResponse anonymous = new MockHttpServletResponse();
    assertFalse(
        guard.preHandle(
            new MockHttpServletRequest("POST", "/ops/knowledge"), anonymous, new Object()));
    assertEquals(401, anonymous.getStatus());
    UserDTO user = new UserDTO();
    user.setId(7L);
    UserHolder.saveUser(user);
    MockHttpServletResponse ordinary = new MockHttpServletResponse();
    assertFalse(
        guard.preHandle(
            new MockHttpServletRequest("POST", "/ops/knowledge"), ordinary, new Object()));
    assertEquals(403, ordinary.getStatus());
    ReflectionTestUtils.setField(guard, "admins", "7");
    assertTrue(
        guard.preHandle(
            new MockHttpServletRequest("POST", "/ops/knowledge"),
            new MockHttpServletResponse(),
            new Object()));
  }

  @Test
  void publicKnowledgeReadCanBeThrottled() throws Exception {
    when(limiter.allow(anyString(), anyInt(), anyLong())).thenReturn(false);
    MockHttpServletResponse response = new MockHttpServletResponse();
    assertFalse(
        guard.preHandle(
            new MockHttpServletRequest("GET", "/knowledge/search"), response, new Object()));
    assertEquals(429, response.getStatus());
  }
}
