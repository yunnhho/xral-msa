package com.xrail.queue.controller;

import com.xrail.common.dto.ApiResponse;
import com.xrail.common.header.Headers;
import com.xrail.queue.dto.QueueEnterRequest;
import com.xrail.queue.service.QueueService;
import com.xrail.queue.sse.SseEmitterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

@Tag(name = "Queue", description = "대기열 진입·상태 조회·SSE 구독 API")
@Slf4j
@RestController
@RequestMapping("/api/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;
    private final SseEmitterRegistry emitterRegistry;

    @Operation(summary = "대기열 진입",
               description = "대기열에 진입. 즉시 ACTIVE이면 Queue-Token 반환, 대기 중이면 순위(rank)/예상 대기시간 반환.")
    @PostMapping("/token")
    public ResponseEntity<ApiResponse<Object>> enter(
            @RequestHeader(Headers.USER_ID) Long userId,
            @RequestHeader(value = Headers.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody QueueEnterRequest request) {

        QueueService.EnterResult result = queueService.enter(userId, request.scope(), idempotencyKey);
        if ("ACTIVE".equals(result.status())) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "scope", request.scope(),
                    "status", "ACTIVE",
                    "queueToken", result.queueToken(),
                    "expiresAt", Instant.ofEpochMilli(result.expiresAt()).toString()
            )));
        }
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "scope", request.scope(),
                "rank", result.rank(),
                "totalWaiting", result.totalWaiting(),
                "expectedWaitSeconds", estimateWait(result.rank()),
                "status", "WAITING"
        )));
    }

    @Operation(summary = "대기열 상태 폴링",
               description = "SSE를 사용할 수 없는 클라이언트를 위한 폴링 fallback. 2회 SSE 실패 시 전환.")
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Object>> status(
            @RequestHeader(Headers.USER_ID) Long userId,
            @RequestParam(defaultValue = "global") String scope) {

        QueueService.QueueStatus status = queueService.getStatus(userId, scope);
        if ("ACTIVE".equals(status.status())) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of(
                    "scope", scope,
                    "status", "ACTIVE",
                    "queueToken", status.queueToken(),
                    "expiresAt", Instant.ofEpochMilli(status.expiresAt()).toString()
            )));
        }
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "scope", scope,
                "rank", status.rank(),
                "totalWaiting", status.totalWaiting(),
                "expectedWaitSeconds", estimateWait(status.rank()),
                "status", "WAITING"
        )));
    }

    @Operation(summary = "SSE 구독",
               description = "Server-Sent Events로 실시간 순위 변동 수신. 최대 10분. ACTIVE 이벤트 수신 시 연결 종료.")
    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(
            @RequestHeader(Headers.USER_ID) Long userId,
            @RequestParam(defaultValue = "global") String scope) {

        SseEmitter emitter = new SseEmitter(600_000L); // Q3: 10분
        emitterRegistry.register(scope, userId, emitter);

        emitter.onCompletion(() -> emitterRegistry.remove(scope, userId));
        emitter.onTimeout(() -> emitterRegistry.remove(scope, userId));
        emitter.onError(e -> emitterRegistry.remove(scope, userId));

        QueueService.QueueStatus current = queueService.getStatus(userId, scope);
        try {
            if ("ACTIVE".equals(current.status())) {
                emitter.send(SseEmitter.event()
                        .name("active")
                        .data(Map.of(
                                "queueToken", current.queueToken(),
                                "expiresAt", Instant.ofEpochMilli(current.expiresAt()).toString()
                        )));
                emitter.complete();
            } else {
                emitter.send(SseEmitter.event()
                        .name("rank")
                        .data(Map.of(
                                "rank", current.rank(),
                                "totalWaiting", current.totalWaiting(),
                                "expectedWaitSeconds", estimateWait(current.rank())
                        )));
            }
        } catch (IOException e) {
            emitter.completeWithError(e);
        }

        return emitter;
    }

    @Operation(summary = "대기열 이탈", description = "대기열에서 자발적으로 이탈. Redis 대기 목록에서 제거.")
    @DeleteMapping("/leave")
    public ResponseEntity<Void> leave(
            @RequestHeader(Headers.USER_ID) Long userId,
            @RequestParam(defaultValue = "global") String scope) {

        queueService.leave(userId, scope);
        return ResponseEntity.noContent().build();
    }

    private int estimateWait(int rank) {
        return (int) Math.ceil((double) rank / 100) * 3;
    }
}
