package com.xrail.notification.controller;

import com.xrail.common.dto.ApiResponse;
import com.xrail.common.header.Headers;
import com.xrail.notification.entity.NotificationLog;
import com.xrail.notification.repository.NotificationLogRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Notification", description = "알림 로그 조회 API")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationLogRepository logRepository;

    @Operation(summary = "내 알림 목록 조회",
               description = "로그인 사용자의 알림 로그를 최신순으로 페이징 조회. 채널별 상태(SENT/FAILED) 포함.")
    @GetMapping
    public ResponseEntity<ApiResponse<Page<NotificationLog>>> list(
            @RequestHeader(Headers.USER_ID) Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<NotificationLog> result = logRepository.findByUserId(
                userId, PageRequest.of(page, size, Sort.by("createdAt").descending()));
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}
