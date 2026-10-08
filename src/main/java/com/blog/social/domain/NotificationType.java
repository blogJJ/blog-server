package com.blog.social.domain;

/**
 * 알림 종류 (spec.md 3.6 알림 종류 표, D-56). 표가 바뀌면 여기도 같이 고친다. 이름은 {@code notifications.type}과 {@code
 * notification_settings.type}에 그대로 저장되므로 이미 쓰는 이름은 바꾸지 않는다.
 *
 * <p>{@code optional}이 false인 알림(위임 요청, 폐쇄 예정, 내 글 삭제됨, 제재 등)은 끌 수 없고 항상 만든다.
 */
public enum NotificationType {
  /** 내 글에 댓글 */
  COMMENT(NotificationTab.COMMENT, true),
  /** 내 댓글에 대댓글 */
  REPLY(NotificationTab.COMMENT, true),
  /** 내 글 좋아요 */
  POST_LIKE(NotificationTab.LIKE, true),
  /** 새 팔로워 */
  FOLLOW(NotificationTab.FOLLOW, true),
  /** 내 블로그 구독 */
  BLOG_SUBSCRIBE(NotificationTab.FOLLOW, true),
  /** 승인제 블로그에 참여 신청이 옴 */
  JOIN_REQUEST(NotificationTab.BLOG, true),
  /** 참여 승인·거절 결과 */
  JOIN_RESULT(NotificationTab.BLOG, true),
  /** 블로그장 위임 요청을 받음 */
  OWNER_TRANSFER_REQUEST(NotificationTab.BLOG, false),
  /** 위임 수락·거절·7일 자동 취소 */
  OWNER_TRANSFER_RESULT(NotificationTab.BLOG, false),
  /** 폐쇄 예정(즉시, 3일 전, 1일 전) */
  BLOG_CLOSING(NotificationTab.BLOG, false),
  /** 폐쇄 철회 */
  BLOG_CLOSE_CANCELED(NotificationTab.BLOG, false),
  /** 블로그가 비공개로 바뀜 */
  BLOG_PRIVATE(NotificationTab.BLOG, false),
  /** 블랙리스트 해제 문의 결과 */
  BLACKLIST_INQUIRY_RESULT(NotificationTab.BLOG, false),
  /** 관리자나 블로그장이 내 글을 삭제함 */
  POST_DELETED(NotificationTab.OPERATION, false),
  /** 내가 한 신고의 처리 결과 */
  REPORT_RESULT(NotificationTab.OPERATION, true),
  /** 블로그 안 경고·정지·정지 해제·강제 퇴장 (BLG-13, ADM-08) */
  MEMBER_SANCTION(NotificationTab.OPERATION, false),
  /** 계정 경고·프로필 초기화·계정 정지·정지 해제 (ADM-08) */
  ACCOUNT_SANCTION(NotificationTab.OPERATION, false),
  /** 블로그장 경고·권한 박탈 (ADM-07) */
  OWNER_SANCTION(NotificationTab.OPERATION, false),
  /** 관리자 공지사항 */
  NOTICE(NotificationTab.OPERATION, true);

  private final NotificationTab tab;
  private final boolean optional;

  NotificationType(NotificationTab tab, boolean optional) {
    this.tab = tab;
    this.optional = optional;
  }

  public NotificationTab getTab() {
    return tab;
  }

  /** 회원이 설정에서 끌 수 있으면 true. */
  public boolean isOptional() {
    return optional;
  }
}
