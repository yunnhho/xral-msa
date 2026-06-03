package com.xrail.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xrail.notification.channel.NotificationChannel;
import com.xrail.notification.entity.NotificationLog;
import com.xrail.notification.repository.NotificationLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private NotificationLogRepository logRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private NotificationChannel inAppChannel;
    private NotificationService notificationService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        inAppChannel = mock(NotificationChannel.class);
        when(inAppChannel.getChannelName()).thenReturn("INAPP");
        objectMapper = new ObjectMapper();
        notificationService = new NotificationService(logRepository, List.of(inAppChannel),
                objectMapper, eventPublisher);
    }

    @Test
    void dispatch_success_savesLogAndPublishesEvent() {
        NotificationLog saved = mock(NotificationLog.class);
        when(saved.getId()).thenReturn(1L);
        when(logRepository.save(any(NotificationLog.class))).thenReturn(saved);

        notificationService.dispatch(42L, "WELCOME", "event-uuid-1", Map.of("name", "홍길동"));

        verify(logRepository).save(any(NotificationLog.class));
        verify(eventPublisher).publishEvent(any(NotificationChannelSendEvent.class));
    }

    @Test
    void dispatch_duplicateCorrelationId_noOpAndNoEvent() {
        // N1: DataIntegrityViolationException → already processed → no event
        doThrow(DataIntegrityViolationException.class)
                .when(logRepository).save(any(NotificationLog.class));

        notificationService.dispatch(42L, "WELCOME", "event-uuid-1", Map.of("name", "홍길동"));

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void dispatch_multipleChannels_savesPerChannel() {
        NotificationChannel smsChannel = mock(NotificationChannel.class);
        when(smsChannel.getChannelName()).thenReturn("SMS");

        notificationService = new NotificationService(logRepository,
                List.of(inAppChannel, smsChannel), objectMapper, eventPublisher);

        NotificationLog saved = mock(NotificationLog.class);
        when(saved.getId()).thenReturn(1L);
        when(logRepository.save(any(NotificationLog.class))).thenReturn(saved);

        notificationService.dispatch(42L, "PAYMENT_COMPLETED", "event-uuid-2", Map.of());

        // One save per channel
        verify(logRepository, times(2)).save(any(NotificationLog.class));
        verify(eventPublisher, times(2)).publishEvent(any(NotificationChannelSendEvent.class));
    }

    @Test
    void dispatch_firstChannelDuplicate_secondChannelStillProcessed() {
        NotificationChannel smsChannel = mock(NotificationChannel.class);
        when(smsChannel.getChannelName()).thenReturn("SMS");

        notificationService = new NotificationService(logRepository,
                List.of(inAppChannel, smsChannel), objectMapper, eventPublisher);

        // First call (INAPP) throws duplicate, second call (SMS) succeeds
        NotificationLog smsLog = mock(NotificationLog.class);
        when(smsLog.getId()).thenReturn(2L);
        when(logRepository.save(any(NotificationLog.class)))
                .thenThrow(DataIntegrityViolationException.class) // INAPP
                .thenReturn(smsLog);                              // SMS

        notificationService.dispatch(42L, "RESERVATION_CREATED", "event-uuid-3", Map.of());

        // SMS was processed
        verify(eventPublisher, times(1)).publishEvent(any(NotificationChannelSendEvent.class));
    }
}
