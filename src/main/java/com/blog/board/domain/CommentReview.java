package com.blog.board.domain;

/**
 * 새 댓글 자동 검사 결과 (comments.review_status, T166, D-133). 검사하지 않았거나 문제없으면 null.
 *
 * <ul>
 *   <li>PENDING: 수상해 보여 숨겨 두고(status HIDDEN) 블로그장 확인을 기다린다. 쓴 사람에게는 그대로 보인다.
 *   <li>APPROVED: 블로그장·글 관리 부블로그장·메인 관리자가 승인해 다시 보인다(status ACTIVE).
 * </ul>
 */
public enum CommentReview {
  PENDING,
  APPROVED
}
