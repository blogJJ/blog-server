package com.blog.auth.domain;

import java.time.Duration;

/** 인증번호 쓰임새와 유효 시간 (USR-02, D-42). */
public enum VerificationPurpose {
  /** 가입 이메일 인증: 10분 */
  SIGNUP(Duration.ofMinutes(10)),
  /** 비밀번호 재설정: 30분 */
  PASSWORD_RESET(Duration.ofMinutes(30));

  private final Duration ttl;

  VerificationPurpose(Duration ttl) {
    this.ttl = ttl;
  }

  public Duration getTtl() {
    return ttl;
  }
}
