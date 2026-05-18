package com.xrail.auth.kafka;

import com.xrail.auth.entity.Member;
import com.xrail.auth.entity.NonMember;
import com.xrail.auth.entity.User;
import com.xrail.common.kafka.Topics;
import com.xrail.common.kafka.event.UserSignedUpEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishUserSignedUp(Member member) {
        UserSignedUpEvent event = new UserSignedUpEvent(
                UUID.randomUUID().toString(),
                Instant.now().toString(),
                MDC.get("traceId"),
                member.getUserId(),
                "MEMBER",
                member.getName(),
                member.getEmail()
        );
        kafkaTemplate.send(Topics.USER_SIGNED_UP, String.valueOf(member.getUserId()), event);
        log.info("Published user.signed-up userId={}", member.getUserId());
    }

    public void publishUserSignedUp(NonMember nonMember) {
        UserSignedUpEvent event = new UserSignedUpEvent(
                UUID.randomUUID().toString(),
                Instant.now().toString(),
                MDC.get("traceId"),
                nonMember.getUserId(),
                "NON_MEMBER",
                nonMember.getName(),
                null
        );
        kafkaTemplate.send(Topics.USER_SIGNED_UP, String.valueOf(nonMember.getUserId()), event);
        log.info("Published user.signed-up userId={} type=NON_MEMBER", nonMember.getUserId());
    }
}
