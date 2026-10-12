package com.blog.auth.domain;

import java.util.Locale;
import java.util.Optional;

/** 소셜 로그인 제공자 (T157, D-128). 1.0은 구글과 깃허브만. 이름의 소문자가 Spring Security의 registrationId와 주소에 쓰인다. */
public enum SocialProvider {
  GOOGLE("Google"),
  GITHUB("GitHub");

  private final String label;

  SocialProvider(String label) {
    this.label = label;
  }

  /** 화면에 보이는 이름 */
  public String getLabel() {
    return label;
  }

  /** registrationId·주소에 쓰는 이름 (google, github) */
  public String getId() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** google, github → 제공자. 모르는 이름이면 빈 값 */
  public static Optional<SocialProvider> fromId(String id) {
    for (SocialProvider p : values()) {
      if (p.getId().equals(id)) {
        return Optional.of(p);
      }
    }
    return Optional.empty();
  }
}
