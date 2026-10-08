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
}
