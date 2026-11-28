package com.hmdp.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hmdp.entity.BusinessOutboxTask;
import com.hmdp.mapper.BusinessOutboxMapper;
import com.hmdp.service.IBusinessEventService;
import com.hmdp.service.impl.OutboxRelayServiceImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxRelayMetricsTest {
  private final BusinessOutboxMapper mapper = mock(BusinessOutboxMapper.class);
  private final EventPublisher publisher = mock(EventPublisher.class);
  private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  private OutboxRelayServiceImpl relay;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    TransactionTemplate transactions = mock(TransactionTemplate.class);
    when(transactions.execute(any()))
        .thenAnswer(call -> ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
    ObjectProvider<EventPublisher> publishers = mock(ObjectProvider.class);
    when(publishers.getObject()).thenReturn(publisher);
    relay =
        new OutboxRelayServiceImpl(
            transactions, publishers, mock(IBusinessEventService.class), metrics);
    ReflectionTestUtils.setField(relay, "mqEnabled", true);
    ReflectionTestUtils.setField(relay, "businessOutboxMapper", mapper);
    BusinessOutboxTask task = new BusinessOutboxTask();
    task.setId(1L);
    task.setKind("ORDER_EXPIRE");
    task.setAggregateId(2L);
    task.setCreatedAt(new Timestamp(0));
    when(mapper.selectClaimableEvents(anyInt())).thenReturn(Collections.singletonList(task));
    when(mapper.renewLease(anyInt(), eq(1L), anyString())).thenReturn(1);
  }

  @Test
  void brokerAcceptanceAndSuccessfulWritebackAreBothCounted() {
    when(mapper.markSent(eq(1L), anyString())).thenReturn(1);
    relay.relayOutboxBatch();
    assertEquals(1.0, metrics.get("hmdp.mq.broker.accepted").counter().count());
    assertEquals(1.0, writeback("updated"));
    assertEquals(1.0, metrics.get("hmdp.mq.sends").tag("result", "success").counter().count());
  }

  @Test
  void brokerAcceptanceWithNoMatchingRowIsNeitherWritebackSuccessNorRetry() {
    when(mapper.markSent(eq(1L), anyString())).thenReturn(0);
    relay.relayOutboxBatch();
    assertEquals(1.0, metrics.get("hmdp.mq.broker.accepted").counter().count());
    assertEquals(1.0, writeback("not_matched"));
    assertEquals(0, metrics.find("hmdp.mq.sends").counters().size());
    verify(mapper, never())
        .recordSendFailure(anyInt(), anyLong(), anyString(), anyString(), anyLong(), anyString());
  }

  @Test
  void writebackExceptionStillEntersExistingRetryPath() {
    when(mapper.markSent(eq(1L), anyString())).thenThrow(new IllegalStateException("database"));
    relay.relayOutboxBatch();
    assertEquals(1.0, metrics.get("hmdp.mq.broker.accepted").counter().count());
    assertEquals(1.0, writeback("error"));
    assertEquals(1.0, metrics.get("hmdp.mq.sends").tag("result", "retry").counter().count());
    verify(mapper)
        .recordSendFailure(eq(1), anyLong(), eq("PENDING"), anyString(), eq(1L), anyString());
  }

  private double writeback(String result) {
    return metrics.get("hmdp.outbox.sent.writeback").tag("result", result).counter().count();
  }
}
