package com.blog.admin.domain;

/**
 * 관리자 활동 종류 (admin_action_logs.action, ADM-06). 이름이 DB에 그대로 저장되므로 이미 쓰는 이름은 바꾸지 않는다. 컬럼은
 * VARCHAR(50)이라 새 종류는 여기에만 더하면 된다.
 */
public enum AdminAction {
  /** 회원 이메일·전화번호 "전체 보기" (ADM-01, 4.4) */
  VIEW_PRIVATE_INFO,
  HIDE_BLOG,
  UNHIDE_BLOG,
  /** 블로그 강제 폐쇄 (7일 유예, D-96) */
  CLOSE_BLOG,
  HIDE_POST,
  DELETE_POST,
  RESTORE_POST,
  HIDE_COMMENT,
  DELETE_COMMENT,
  /** 블로그장 경고 (ADM-07) */
  OWNER_WARN,
  /** 블로그장 권한 박탈 (ADM-07) */
  OWNER_DEMOTE,
  /** 계정 경고 (ADM-08) */
  ACCOUNT_WARN,
  /** 프로필 초기화 (ADM-08) */
  PROFILE_RESET,
  /** 계정 정지 (ADM-08) */
  ACCOUNT_SUSPEND,
  /** 계정 정지 해제 (ADM-08) */
  ACCOUNT_RELEASE,
  /** 블로그장 정지 중인 블로그의 멤버 경고·정지·강제 퇴장을 대신 처리 (ADM-08, D-100) */
  MEMBER_WARN,
  MEMBER_SUSPEND,
  MEMBER_KICK,
  /** 신고 처리 (ADM-04) */
  RESOLVE_REPORT,
  /** 메인 공지 (BRD-10) */
  POST_NOTICE
}
