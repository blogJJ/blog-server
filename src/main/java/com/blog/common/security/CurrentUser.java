package com.blog.common.security;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** 이번 요청의 로그인 회원. 비회원이면 비어 있다. */
public final class CurrentUser {

  private CurrentUser() {}

  public static Optional<AuthUser> get() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof AuthUser user) {
      return Optional.of(user);
    }
    return Optional.empty();
  }

  public static Optional<Long> id() {
    return get().map(AuthUser::id);
  }
}
