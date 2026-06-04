package com.xrail.common.security;

/**
 * 권한 역할 상수. JWT의 role 클레임 값(= auth-service UserRole.name())과 동일해야 한다.
 * Gateway가 X-User-Role 헤더로 주입하며, downstream의 운영자 가드는 이 값으로 비교한다.
 */
public final class Roles {

    private Roles() {}

    public static final String ADMIN = "ROLE_ADMIN";
}
