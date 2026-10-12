package com.blog.blog.domain;

/** 블로그장 위임 요청 상태: 대기 / 수락 / 거절 / 폐쇄로 취소 / 7일 지나 자동 취소 (BLG-08, D-31) */
public enum TransferStatus {
  PENDING,
  ACCEPTED,
  REJECTED,
  CANCELED,
  EXPIRED
}
