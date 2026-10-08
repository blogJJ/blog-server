package com.blog.board.domain;

/** 댓글 상태. HIDDEN은 관리자가 숨긴 것(ADM-03), DELETED는 지운 것(30일 보관). */
public enum CommentStatus {
  ACTIVE,
  HIDDEN,
  DELETED
}
