package com.blog.auth.service;

import com.blog.auth.domain.SocialProvider;
import java.io.Serializable;
import java.time.Instant;

/**
 * 소셜 로그인은 됐지만 아직 가입하지 않은 사람 (T157, D-128). 서버 세션에만 두고 화면에는 제공자·이메일·이름만 보여 준다. 화면이 보낸 제공자 회원 번호는 믿지
 * 않는다.
 *
 * @param verifiedEmail 제공자가 인증한 이메일. 가입하면 로그인 이메일이 된다
 * @param name 제공자에 등록된 이름. 가입 화면에 미리 채운다. 없으면 null
 * @param rememberMe 로그인 화면에서 "로그인 유지"를 체크하고 왔는지
 * @param expiresAt 이 시각이 지나면 처음부터 다시
 */
public record PendingSocialSignup(
    SocialProvider provider,
    String providerUserId,
    String verifiedEmail,
    String name,
    boolean rememberMe,
    Instant expiresAt)
    implements Serializable {

  public boolean isExpiredAt(Instant now) {
    return !expiresAt.isAfter(now);
  }
}
