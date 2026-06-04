package com.xrail.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xrail.notification.channel.NotificationChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private NotificationLogWriter logWriter;

    private NotificationChannel inAppChannel;
    private NotificationService notificationService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        inAppChannel = mock(NotificationChannel.class);
        when(inAppChannel.getChannelName()).thenReturn("INAPP");
        objectMapper = new ObjectMapper();
        notificationService = new NotificationService(List.of(inAppChannel), objectMapper, logWriter);
    }

    @Test
    void dispatch_success_writesPerChannel() {
        notificationService.dispatch(42L, "WELCOME", "event-uuid-1", Map.of("name", "홍길동"));

        verify(logWriter).saveAndPublish(eq(42L), eq("INAPP"), eq("WELCOME"), eq("event-uuid-1"), anyString());
    }

    @Test
    void dispatch_duplicateCorrelationId_swallowedNoThrow() {
        // N1: DataIntegrityViolationException → already processed → swallow, no propagation
        doThrow(DataIntegrityViolationException.class)
                .when(logWriter).saveAndPublish(anyLong(), anyString(), anyString(), anyString(), anyString());

        assertThatCode(() ->
                notificationService.dispatch(42L, "WELCOME", "event-uuid-1", Map.of("name", "홍길동")))
                .doesNotThrowAnyException();

        verify(logWriter).saveAndPublish(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void dispatch_multipleChannels_writesPerChannel() {
        NotificationChannel smsChannel = mock(NotificationChannel.class);
        when(smsChannel.getChannelName()).thenReturn("SMS");

        notificationService = new NotificationService(
                List.of(inAppChannel, smsChannel), objectMapper, logWriter);

        notificationService.dispatch(42L, "PAYMENT_COMPLETED", "event-uuid-2", Map.of());

        verify(logWriter, times(2)).saveAndPublish(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void dispatch_firstChannelDuplicate_secondChannelStillProcessed() {
        NotificationChannel smsChannel = mock(NotificationChannel.class);
        when(smsChannel.getChannelName()).thenReturn("SMS");

        notificationService = new NotificationService(
                List.of(inAppChannel, smsChannel), objectMapper, logWriter);

        // INAPP throws duplicate; SMS must still be attempted (isolated transactions)
        doThrow(DataIntegrityViolationException.class)
                .when(logWriter).saveAndPublish(anyLong(), eq("INAPP"), anyString(), anyString(), anyString());

        notificationService.dispatch(42L, "RESERVATION_CREATED", "event-uuid-3", Map.of());

        verify(logWriter).saveAndPublish(anyLong(), eq("SMS"), anyString(), anyString(), anyString());
    }
}
