package com.blog.auth.service;

import com.blog.auth.domain.SocialProvider;
import com.blog.auth.domain.User;
import java.util.Map;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * 제공자가 알려 준 회원 정보 (T157, D-128). Spring Security가 제공자에게서 직접 받은 값으로만 만들고, 화면이 보낸 값은 쓰지 않는다.
 *
 * @param provider 제공자
 * @param providerUserId 제공자의 회원 번호 (구글 sub, 깃허브 id)
 * @param verifiedEmail 제공자가 인증했다고 알려 준 이메일(소문자). 인증 안 됐거나 없으면 null
 * @param name 제공자에 등록된 이름. 없으면 null
 */
public record SocialProfile(
    SocialProvider provider, String providerUserId, String verifiedEmail, String name) {

  /** 깃허브는 /user에 인증 여부가 없어서 SocialOAuth2UserService가 /user/emails로 확인한 이메일을 이 이름으로 넣어 둔다 */
  public static final String GITHUB_VERIFIED_EMAIL = "verified_primary_email";

  public SocialProfile {
    verifiedEmail = blankToNull(User.normalizeEmail(verifiedEmail));
    name = blankToNull(name == null ? null : name.strip());
  }

  /** 로그인을 마친 OAuth2User에서 꺼낸다. 회원 번호가 없으면 IllegalArgumentException */
  public static SocialProfile from(SocialProvider provider, OAuth2User user) {
    Map<String, Object> attrs = user.getAttributes();
    String id;
    String email;
    String name;
    switch (provider) {
      case GOOGLE -> {
        id = text(attrs.get("sub"));
        Object verified =
            user instanceof OidcUser oidc ? oidc.getEmailVerified() : attrs.get("email_verified");
        email = isTrue(verified) ? text(attrs.get("email")) : null;
        name = text(attrs.get("name"));
      }
      case GITHUB -> {
        id = text(attrs.get("id"));
        email = text(attrs.get(GITHUB_VERIFIED_EMAIL));
        name = text(attrs.get("name"));
        if (name == null) {
          name = text(attrs.get("login"));
        }
      }
      default -> throw new IllegalArgumentException("unknown provider");
    }
    if (id == null || id.isBlank() || id.length() > 100) {
      throw new IllegalArgumentException("provider user id missing");
    }
    return new SocialProfile(provider, id, email, name);
  }

  private static boolean isTrue(Object value) {
    return Boolean.TRUE.equals(value) || "true".equals(value);
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }

  private static String blankToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }
}
