package com.xrail.common.header;

public final class Headers {

    private Headers() {}

    /** Gateway가 JWT에서 추출하여 downstream으로 주입. 클라이언트 전송 시 Gateway가 제거. */
    public static final String USER_ID   = "X-User-Id";
    public static final String USER_ROLE = "X-User-Role";
    public static final String USER_NAME = "X-User-Name";

    /** queue-service가 발급한 HMAC 토큰. POST /api/reservations 시 필요. */
    public static final String QUEUE_TOKEN = "X-Queue-Token";

    /** CAPTCHA 공급자 토큰. */
    public static final String CAPTCHA_TOKEN = "X-Captcha-Token";

    /** 중복 요청 방지 UUID. 클라이언트가 생성. */
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
}
