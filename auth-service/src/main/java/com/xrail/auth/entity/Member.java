package com.xrail.auth.entity;

import com.xrail.auth.entity.enums.UserRole;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;

@Getter
@Entity
@Table(name = "members")
@DiscriminatorValue("Member")
@PrimaryKeyJoinColumn(name = "user_id")
public class Member extends User {

    @Column(name = "login_id", length = 50, unique = true)
    private String loginId;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 100, unique = true)
    private String email;

    @Column(length = 20)
    private String phone;

    @Column(name = "birth_date", length = 8)
    private String birthDate;

    @Column(name = "social_provider", length = 20)
    private String socialProvider;

    @Column(name = "social_id", length = 100)
    private String socialId;

    protected Member() {
        super();
    }

    @Builder
    public Member(String loginId, String passwordHash, String name, String email,
                  String phone, String birthDate, String socialProvider, String socialId,
                  UserRole role) {
        super(role != null ? role : UserRole.ROLE_MEMBER);
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.birthDate = birthDate;
        this.socialProvider = socialProvider;
        this.socialId = socialId;
    }

    public void updatePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void updateSocial(String provider, String socialId) {
        this.socialProvider = provider;
        this.socialId = socialId;
    }
}
