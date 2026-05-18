package com.xrail.auth.oauth2;

import com.xrail.auth.entity.Member;
import com.xrail.auth.entity.enums.UserRole;
import com.xrail.auth.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final MemberRepository memberRepository;

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        String registrationId = userRequest.getClientRegistration().getRegistrationId().toUpperCase();

        OAuth2UserInfo userInfo = switch (registrationId) {
            case "KAKAO" -> new KakaoUserInfo(oAuth2User.getAttributes());
            case "NAVER" -> new NaverUserInfo(oAuth2User.getAttributes());
            default -> throw new OAuth2AuthenticationException("Unsupported provider: " + registrationId);
        };

        Member member = memberRepository.findBySocialProviderAndSocialId(userInfo.getProvider(), userInfo.getProviderId())
                .orElseGet(() -> createMember(userInfo));

        return new CustomOAuth2User(oAuth2User.getAttributes(), member);
    }

    private Member createMember(OAuth2UserInfo userInfo) {
        Member existing = userInfo.getEmail() != null
                ? memberRepository.findByEmail(userInfo.getEmail()).orElse(null)
                : null;

        if (existing != null) {
            existing.updateSocial(userInfo.getProvider(), userInfo.getProviderId());
            return memberRepository.save(existing);
        }

        return memberRepository.save(Member.builder()
                .email(userInfo.getEmail())
                .name(userInfo.getName() != null ? userInfo.getName() : "소셜유저")
                .socialProvider(userInfo.getProvider())
                .socialId(userInfo.getProviderId())
                .role(UserRole.ROLE_MEMBER)
                .build());
    }
}
