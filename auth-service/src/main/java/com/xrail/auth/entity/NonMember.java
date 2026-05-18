package com.xrail.auth.entity;

import com.xrail.auth.entity.enums.UserRole;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;

@Getter
@Entity
@Table(name = "non_members")
@DiscriminatorValue("NonMember")
@PrimaryKeyJoinColumn(name = "user_id")
public class NonMember extends User {

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(nullable = false, length = 255)
    private String password;

    @Column(name = "access_code", nullable = false, length = 10, unique = true)
    private String accessCode;

    protected NonMember() {
        super();
    }

    @Builder
    public NonMember(String name, String phone, String password, String accessCode) {
        super(UserRole.ROLE_NON_MEMBER);
        this.name = name;
        this.phone = phone;
        this.password = password;
        this.accessCode = accessCode;
    }
}
