package com.xrail.auth.repository;

import com.xrail.auth.entity.NonMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NonMemberRepository extends JpaRepository<NonMember, Long> {
    Optional<NonMember> findByAccessCode(String accessCode);
    Optional<NonMember> findByAccessCodeAndPhone(String accessCode, String phone);
}
