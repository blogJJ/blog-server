package com.blog.auth.domain;

/** 회원 역할. ADMIN은 메인 관리자이고 블로그 활동을 못 한다 (D-90) */
public enum UserRole {
  USER,
  ADMIN
}
