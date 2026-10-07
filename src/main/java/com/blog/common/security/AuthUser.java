package com.blog.common.security;

import com.blog.auth.domain.UserRole;

/**
 * 로그인한 회원. 컨트롤러에서 {@code @AuthenticationPrincipal AuthUser user}로 받는다.
 *
 * @param id 회원 번호
 * @param role 이번 요청에서 DB로 읽은 역할
 */
public record AuthUser(Long id, UserRole role) {}
