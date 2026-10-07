package com.blog.blog.domain;

/** 폐쇄 사유: 블로그장 폐쇄 / 블로그장 박탈 / 관리자 강제 폐쇄 */
public enum CloseReason {
  OWNER,
  OWNER_DEMOTED,
  ADMIN
}
