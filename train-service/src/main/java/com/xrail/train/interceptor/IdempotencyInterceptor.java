package com.xrail.train.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xrail.common.dto.ApiResponse;
import com.xrail.common.exception.ErrorCode;
import com.xrail.common.header.Headers;
import com.xrail.train.dto.ReservationResponse;
import com.xrail.train.entity.Seat;
import com.xrail.train.entity.Ticket;
import com.xrail.train.repository.ReservationRepository;
import com.xrail.train.repository.SeatRepository;
import com.xrail.train.repository.TicketRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

/**
 * T2: Redis SETNX로 중복 예약 요청 차단. 동시 중복 요청은 409, 완료 후 재요청은 기존 응답 반환.
 * QueueTokenInterceptor 이후, @Transactional service 이전에 실행.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyInterceptor implements HandlerInterceptor {

    private static final String IDEM_KEY_PREFIX = "idem:reservation:";
    private static final Duration IDEM_TTL = Duration.ofMinutes(5);

    private final RedissonClient redissonClient;
    private final ReservationRepository reservationRepository;
    private final TicketRepository ticketRepository;
    private final SeatRepository seatRepository;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!HttpMethod.POST.matches(request.getMethod())) return true;

        String idempotencyKey = request.getHeader(Headers.IDEMPOTENCY_KEY);
        if (idempotencyKey == null || idempotencyKey.isBlank()) return true;

        String redisKey = IDEM_KEY_PREFIX + idempotencyKey;
        boolean isNew = redissonClient.getBucket(redisKey).setIfAbsent("PROCESSING", IDEM_TTL);

        if (isNew) {
            // 최초 요청 — 서비스 레이어로 진행
            return true;
        }

        // 중복 요청 — DB에서 기존 결과 조회
        return reservationRepository.findByIdempotencyKey(idempotencyKey)
                .map(existing -> {
                    var tickets = ticketRepository.findByReservationReservationId(existing.getReservationId());
                    var seatIds = tickets.stream().map(Ticket::getSeatId).toList();
                    var seatNumberMap = seatRepository.findAllById(seatIds).stream()
                            .collect(java.util.stream.Collectors.toMap(Seat::getSeatId, Seat::getSeatNumber));
                    ReservationResponse body = ReservationResponse.of(existing, tickets, seatNumberMap);
                    try {
                        response.setStatus(HttpServletResponse.SC_OK);
                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        response.setCharacterEncoding("UTF-8");
                        objectMapper.writeValue(response.getWriter(), ApiResponse.ok(body));
                    } catch (Exception e) {
                        log.error("Failed to write idempotent response key={}", idempotencyKey, e);
                    }
                    return false; // 서비스 진행 중단
                })
                .orElseGet(() -> {
                    // 아직 처리 중 (SETNX 충돌) — 409 반환
                    log.warn("Idempotency conflict key={}", idempotencyKey);
                    try {
                        response.setStatus(HttpServletResponse.SC_CONFLICT);
                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        response.setCharacterEncoding("UTF-8");
                        objectMapper.writeValue(response.getWriter(),
                                ApiResponse.error(ErrorCode.IDEMPOTENCY_CONFLICT.getCode(),
                                        ErrorCode.IDEMPOTENCY_CONFLICT.getMessage()));
                    } catch (Exception e) {
                        log.error("Failed to write conflict response", e);
                    }
                    return false;
                });
    }

    /**
     * T2: 요청이 성공(2xx)으로 끝나지 않으면 PROCESSING 키를 삭제하여 재시도를 허용한다.
     * 성공 시에는 키를 유지 — 다음 중복 요청이 DB 조회 경로를 타게 된다.
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (!HttpMethod.POST.matches(request.getMethod())) return;
        String idempotencyKey = request.getHeader(Headers.IDEMPOTENCY_KEY);
        if (idempotencyKey == null || idempotencyKey.isBlank()) return;

        boolean success = ex == null && response.getStatus() >= 200 && response.getStatus() < 300;
        if (!success) {
            String redisKey = IDEM_KEY_PREFIX + idempotencyKey;
            Object value = redissonClient.getBucket(redisKey).get();
            if ("PROCESSING".equals(value)) {
                redissonClient.getBucket(redisKey).delete();
                log.debug("Cleared PROCESSING idempotency key={} status={}", idempotencyKey, response.getStatus());
            }
        }
    }
}
