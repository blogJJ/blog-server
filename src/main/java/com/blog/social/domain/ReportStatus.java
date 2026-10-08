package com.blog.social.domain;

/**
 * 신고 처리 결과 (reports.status). 블로그장은 NO_ISSUE·WARNED·SUSPENDED·KICKED, 나머지는 메인 관리자(ADM-04, ADM-08).
 */
public enum ReportStatus {
  PENDING,
  NO_ISSUE,
  WARNED,
  SUSPENDED,
  KICKED,
  OWNER_DEMOTED,
  BLOG_CLOSED,
  PROFILE_RESET,
  ACCOUNT_SUSPENDED
}
