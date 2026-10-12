package com.blog.admin.domain;

/** 계정 제재 종류: 경고 / 프로필 초기화 / 계정 정지 (user_sanctions.type, ADM-08) */
public enum UserSanctionType {
  WARNING,
  PROFILE_RESET,
  SUSPENSION
}
