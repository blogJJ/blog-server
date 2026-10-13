package com.blog.board.domain;

/** 자동 검사가 댓글을 숨긴 이유 (comments.review_reason, T166, D-133). */
public enum CommentReviewReason {
  /** 도배, 의미 없는 글, 링크 뿌리기, 사기·피싱 */
  SPAM,
  /** 상품·서비스·도박·성인 사이트 홍보, 판매용 연락처 */
  AD,
  /** 욕설, 비하, 괴롭힘, 위협 */
  ABUSE,
  /** 전화번호·주소·주민등록번호·계좌번호 같은 남의 개인정보 노출 */
  PERSONAL_INFO,
  /** 그 밖에 수상한 것 */
  OTHER
}
