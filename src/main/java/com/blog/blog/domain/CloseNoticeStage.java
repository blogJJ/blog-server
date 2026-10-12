package com.blog.blog.domain;

/** 폐쇄 알림 단계: 즉시 / 3일 전 / 1일 전 / 철회 (BLG-09, D-13) */
public enum CloseNoticeStage {
  IMMEDIATE,
  D3,
  D1,
  REVOKED
}
