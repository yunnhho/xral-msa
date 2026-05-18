package com.xrail.auth.repository;

import com.xrail.auth.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {
    Optional<Member> findByEmail(String email);
    Optional<Member> findByLoginId(String loginId);
    Optional<Member> findBySocialProviderAndSocialId(String socialProvider, String socialId);
    boolean existsByEmail(String email);
}
