package com.xrail.queue.controller;

import com.xrail.common.dto.ApiResponse;
import com.xrail.common.header.Headers;
import com.xrail.queue.dto.QueueEnterRequest;
import com.xrail.queue.service.QueueService;
import com.xrail.queue.sse.SseEmitterRegistry;
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

@Slf4j
@RestController
@RequestMapping("/api/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;
    private final SseEmitterRegistry emitterRegistry;

    /** POST /api/queue/token — 대기열 진입 (캡차는 Gateway에서 선처리) */
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

    /** GET /api/queue/status — 폴링 fallback */
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

    /** GET /api/queue/subscribe — SSE 구독 */
    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(
            @RequestHeader(Headers.USER_ID) Long userId,
            @RequestParam(defaultValue = "global") String scope) {

        SseEmitter emitter = new SseEmitter(600_000L); // Q3: 10분
        emitterRegistry.register(scope, userId, emitter);

        emitter.onCompletion(() -> emitterRegistry.remove(scope, userId));
        emitter.onTimeout(() -> emitterRegistry.remove(scope, userId));
        emitter.onError(e -> emitterRegistry.remove(scope, userId));

        // 초기 rank 이벤트 즉시 전송
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

    /** DELETE /api/queue/leave */
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
