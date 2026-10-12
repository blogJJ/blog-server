package com.blog.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.blog.auth.domain.SocialProvider;
import com.blog.common.config.SocialLoginProperties;
import com.blog.common.config.SocialLoginProperties.Client;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

/** 제공자 회원 정보 읽기와 켜진 제공자 (T157, D-128). 네트워크는 쓰지 않는다. */
class SocialProfileTest {

  @Test
  void googleEmailCountsOnlyWhenVerified() {
    SocialProfile verified =
        SocialProfile.from(
            SocialProvider.GOOGLE,
            user(
                Map.of(
                    "sub",
                    "123",
                    "email",
                    "A@Example.com",
                    "email_verified",
                    true,
                    "name",
                    " 홍길동 "),
                "sub"));
    assertThat(verified.providerUserId()).isEqualTo("123");
    assertThat(verified.verifiedEmail()).isEqualTo("a@example.com");
    assertThat(verified.name()).isEqualTo("홍길동");

    SocialProfile unverified =
        SocialProfile.from(
            SocialProvider.GOOGLE,
            user(Map.of("sub", "123", "email", "a@example.com", "email_verified", false), "sub"));
    assertThat(unverified.verifiedEmail()).isNull();
  }

  @Test
  void githubUsesVerifiedPrimaryEmailAndFallsBackToLogin() {
    SocialProfile profile =
        SocialProfile.from(
            SocialProvider.GITHUB,
            user(
                Map.of(
                    "id",
                    42,
                    "login",
                    "octocat",
                    "email",
                    "public@example.com",
                    SocialProfile.GITHUB_VERIFIED_EMAIL,
                    "Primary@Example.com"),
                "id"));
    assertThat(profile.providerUserId()).isEqualTo("42");
    // /user의 공개 이메일은 인증 여부를 몰라 쓰지 않는다
    assertThat(profile.verifiedEmail()).isEqualTo("primary@example.com");
    assertThat(profile.name()).isEqualTo("octocat");

    SocialProfile noEmail =
        SocialProfile.from(
            SocialProvider.GITHUB, user(Map.of("id", 42, "email", "public@example.com"), "id"));
    assertThat(noEmail.verifiedEmail()).isNull();
  }

  @Test
  void missingProviderIdIsRejected() {
    assertThatThrownBy(
            () ->
                SocialProfile.from(
                    SocialProvider.GOOGLE, user(Map.of("name", "x", "id", "1"), "id")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void githubEmailsPickPrimaryVerifiedOnly() {
    assertThat(
            GithubEmailClient.pickPrimaryVerified(
                List.of(
                    Map.of("email", "a@example.com", "primary", false, "verified", true),
                    Map.of("email", "b@example.com", "primary", true, "verified", true))))
        .isEqualTo("b@example.com");
    assertThat(
            GithubEmailClient.pickPrimaryVerified(
                List.of(Map.of("email", "b@example.com", "primary", true, "verified", false))))
        .isNull();
    assertThat(GithubEmailClient.pickPrimaryVerified(null)).isNull();
  }

  @SuppressWarnings("unchecked")
  @Test
  void githubUserServiceAddsVerifiedEmailButGoogleIsLeftAlone() {
    OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate = mock(OAuth2UserService.class);
    GithubEmailClient emails = mock(GithubEmailClient.class);
    SocialOAuth2UserService service = new SocialOAuth2UserService(delegate, emails);

    OAuth2UserRequest github = request(registrations().findByRegistrationId("github"));
    when(delegate.loadUser(github)).thenReturn(user(Map.of("id", 7, "login", "jj"), "id"));
    when(emails.primaryVerifiedEmail("token")).thenReturn("jj@example.com");
    OAuth2User loaded = service.loadUser(github);
    assertThat(loaded.<String>getAttribute(SocialProfile.GITHUB_VERIFIED_EMAIL))
        .isEqualTo("jj@example.com");
    assertThat(loaded.getName()).isEqualTo("7");

    OAuth2UserRequest google = request(registrations().findByRegistrationId("google"));
    when(delegate.loadUser(google)).thenReturn(user(Map.of("sub", "1"), "sub"));
    service.loadUser(google);
    // 구글은 깃허브 이메일 API를 부르지 않는다
    verify(emails, times(1)).primaryVerifiedEmail(anyString());
  }

  @Test
  void providerWithoutIdOrSecretIsDisabled() {
    SocialClientRegistrations none =
        new SocialClientRegistrations(new SocialLoginProperties(null, null));
    assertThat(none.isEmpty()).isTrue();
    assertThat(none.findByRegistrationId("google")).isNull();

    SocialClientRegistrations googleOnly =
        new SocialClientRegistrations(
            new SocialLoginProperties(new Client("gid", "gsecret"), new Client("hid", "")));
    assertThat(googleOnly.enabledProviders()).containsExactly(SocialProvider.GOOGLE);
    assertThat(googleOnly.findByRegistrationId("github")).isNull();
    ClientRegistration google = googleOnly.findByRegistrationId("google");
    assertThat(google.getRedirectUri())
        .isEqualTo("{baseUrl}/{action}/oauth2/code/{registrationId}");
    assertThat(google.getScopes()).contains("openid", "email", "profile");
    assertThat(new Client("id", "s3cr3t").toString()).doesNotContain("s3cr3t");
  }

  @Test
  void githubAsksForEmailScope() {
    assertThat(registrations().findByRegistrationId("github").getScopes())
        .containsExactlyInAnyOrder("read:user", "user:email");
  }

  private static SocialClientRegistrations registrations() {
    return new SocialClientRegistrations(
        new SocialLoginProperties(new Client("gid", "gsecret"), new Client("hid", "hsecret")));
  }

  private static OAuth2UserRequest request(ClientRegistration registration) {
    return new OAuth2UserRequest(
        registration,
        new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "token",
            Instant.now(),
            Instant.now().plusSeconds(60)));
  }

  private static OAuth2User user(Map<String, Object> attrs, String nameKey) {
    return new DefaultOAuth2User(
        List.of(new SimpleGrantedAuthority("OAUTH2_USER")), attrs, nameKey);
  }
}
