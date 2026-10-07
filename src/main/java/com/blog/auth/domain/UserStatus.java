package com.blog.auth.domain;

/** 계정 상태. 탈퇴해도 행은 지우지 않는다 (USR-05) */
public enum UserStatus {
  ACTIVE,
  WITHDRAWN
}
