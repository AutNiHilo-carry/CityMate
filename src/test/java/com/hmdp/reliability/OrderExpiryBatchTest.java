package com.hmdp.reliability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IBusinessOutboxService;
import com.hmdp.service.impl.OrderLifecycleServiceImpl;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

class OrderExpiryBatchTest {
  @Test
  void aFullPageOfBrokenOrdersDoesNotPermanentlyHideTheNextPage() {
    OrderLifecycleServiceImpl service =
        spy(
            new OrderLifecycleServiceImpl(
                mock(IBusinessOutboxService.class), mock(TransactionTemplate.class)));
    VoucherOrderMapper mapper = mock(VoucherOrderMapper.class);
    ReflectionTestUtils.setField(service, "voucherOrderMapper", mapper);
    List<Long> first = LongStream.rangeClosed(1, 200).boxed().collect(Collectors.toList());
    when(mapper.selectExpiredUnpaidOrders(0)).thenReturn(first);
    when(mapper.selectExpiredUnpaidOrders(200)).thenReturn(Collections.singletonList(201L));
    doThrow(new IllegalStateException("isolated broken order"))
        .when(service)
        .closeExpiredOrder(org.mockito.ArgumentMatchers.anyLong());
    doReturn(true).when(service).closeExpiredOrder(201L);
    assertDoesNotThrow(service::expireOverdueOrders);
    assertDoesNotThrow(service::expireOverdueOrders);
    verify(service).closeExpiredOrder(201L);
    assertDoesNotThrow(service::expireOverdueOrders);
    verify(mapper, org.mockito.Mockito.times(2)).selectExpiredUnpaidOrders(0);
  }

  @Test
  void oneBrokenOrderDoesNotStarveLaterExpiredOrders() {
    OrderLifecycleServiceImpl service =
        spy(
            new OrderLifecycleServiceImpl(
                mock(IBusinessOutboxService.class), mock(TransactionTemplate.class)));
    VoucherOrderMapper mapper = mock(VoucherOrderMapper.class);
    ReflectionTestUtils.setField(service, "voucherOrderMapper", mapper);
    when(mapper.selectExpiredUnpaidOrders(0)).thenReturn(Arrays.asList(11L, 12L));
    doThrow(new IllegalStateException("isolated broken activity"))
        .when(service)
        .closeExpiredOrder(11L);
    doReturn(true).when(service).closeExpiredOrder(12L);
    assertDoesNotThrow(service::expireOverdueOrders);
    verify(service).closeExpiredOrder(12L);
  }
}
