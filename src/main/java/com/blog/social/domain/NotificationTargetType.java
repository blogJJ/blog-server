package com.blog.social.domain;

/** 알림을 누르면 갈 대상 종류. 대상이 없는 알림(공지 등)은 null. */
public enum NotificationTargetType {
  USER,
  BLOG,
  POST,
  COMMENT,
  REPORT
}
