package com.blog.board.domain;

import com.blog.auth.domain.User;
import com.blog.common.domain.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 댓글 (comments, T059, BRD-06). 대댓글은 1단계까지라 부모는 언제나 첫 댓글이고, 누구에게 답했는지는 {@code replyToUser}에 둔다
 * (D-78, D-87). 지워도 행은 남기고 DELETED로 바꾼다.
 */
@Entity
@Table(name = "comments")
public class Comment extends BaseTimeEntity {

  public static final int CONTENT_MAX = 500;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "post_id", nullable = false)
  private Post post;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "author_id", nullable = false)
  private User author;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "parent_id")
  private Comment parent;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "reply_to_user_id")
  private User replyToUser;

  @Column(nullable = false, length = 500)
  private String content;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private CommentStatus status = CommentStatus.ACTIVE;

  @Column(name = "is_edited", nullable = false)
  private boolean edited;

  @Column(name = "deleted_at")
  private LocalDateTime deletedAt;

  /** 자동 검사 결과 (T166, D-133). 검사하지 않았거나 문제없으면 null */
  @Enumerated(EnumType.STRING)
  @Column(name = "review_status")
  private CommentReview reviewStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "review_reason")
  private CommentReviewReason reviewReason;

  /** 자동 검사가 숨긴 시각 */
  @Column(name = "held_at")
  private LocalDateTime heldAt;

  protected Comment() {}

  public Comment(Post post, User author, Comment parent, User replyToUser, String content) {
    this.post = post;
    this.author = author;
    this.parent = parent;
    this.replyToUser = replyToUser;
    this.content = content;
  }

  /** 고치면 "수정됨"이 붙는다. */
  public void edit(String content) {
    this.content = content;
    this.edited = true;
  }

  public void delete(LocalDateTime now) {
    this.status = CommentStatus.DELETED;
    this.deletedAt = now;
  }

  /** 관리자 숨김 (ADM-03). 화면에는 삭제된 댓글처럼 보인다. */
  public void hide() {
    this.status = CommentStatus.HIDDEN;
  }

  /** 자동 검사가 수상하다고 본 댓글을 숨기고 블로그장 확인을 기다린다 (T166, D-133). 다른 사람에게는 관리자 숨김처럼 빠지고, 쓴 사람에게는 그대로 보인다. */
  public void hold(CommentReviewReason reason, LocalDateTime now) {
    this.status = CommentStatus.HIDDEN;
    this.reviewStatus = CommentReview.PENDING;
    this.reviewReason = reason;
    this.heldAt = now;
  }

  /** 확인을 기다리던 댓글을 승인해 다시 보이게 한다. */
  public void approve() {
    this.status = CommentStatus.ACTIVE;
    this.reviewStatus = CommentReview.APPROVED;
  }

  /** 자동 검사로 숨겨져 확인을 기다리는 중 */
  public boolean isHeld() {
    return status == CommentStatus.HIDDEN && reviewStatus == CommentReview.PENDING;
  }

  /** 이 사람에게 보이는지. 확인을 기다리는 댓글은 쓴 사람에게만 보인다. */
  public boolean isShownTo(Long viewerId) {
    return isActive() || (isHeld() && viewerId != null && isWrittenBy(viewerId));
  }

  public boolean isActive() {
    return status == CommentStatus.ACTIVE;
  }

  public boolean isWrittenBy(Long userId) {
    return author.getId().equals(userId);
  }

  public Long getId() {
    return id;
  }

  public Post getPost() {
    return post;
  }

  public User getAuthor() {
    return author;
  }

  public Comment getParent() {
    return parent;
  }

  public User getReplyToUser() {
    return replyToUser;
  }

  public String getContent() {
    return content;
  }

  public CommentStatus getStatus() {
    return status;
  }

  public boolean isEdited() {
    return edited;
  }

  public LocalDateTime getDeletedAt() {
    return deletedAt;
  }

  public CommentReview getReviewStatus() {
    return reviewStatus;
  }

  public CommentReviewReason getReviewReason() {
    return reviewReason;
  }

  public LocalDateTime getHeldAt() {
    return heldAt;
  }
}
