package com.blog.board.domain;

import com.blog.auth.domain.User;
import com.blog.blog.domain.Blog;
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
 * 게시글 (posts, T057, BRD-01). 본문은 마크다운 원문을 저장하고, 보여 줄 때 {@code ContentRenderer}가 걸러낸 HTML로 바꾼다. 삭제는
 * 행을 지우지 않고 DELETED로 바꿔 30일 보관한다.
 */
@Entity
@Table(name = "posts")
public class Post extends BaseTimeEntity {

  public static final int TITLE_MAX = 30;
  public static final int CONTENT_MAX = 5_000;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "blog_id", nullable = false)
  private Blog blog;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "author_id", nullable = false)
  private User author;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "category_id")
  private Category category;

  @Column(nullable = false, length = 30)
  private String title;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String content;

  @Column(name = "is_notice", nullable = false)
  private boolean notice;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private PostStatus status = PostStatus.PUBLISHED;

  /** 작성자가 블로그를 떠나거나 강퇴되면 "탈퇴한 계정"으로 보인다. */
  @Column(name = "author_hidden", nullable = false)
  private boolean authorHidden;

  @Column(name = "view_count", nullable = false)
  private int viewCount;

  @Column(name = "like_count", nullable = false)
  private int likeCount;

  @Column(name = "comment_count", nullable = false)
  private int commentCount;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "deleted_by")
  private User deletedBy;

  @Column(name = "deleted_at")
  private LocalDateTime deletedAt;

  protected Post() {}

  public Post(
      Blog blog, User author, Category category, String title, String content, boolean notice) {
    this.blog = blog;
    this.author = author;
    this.category = category;
    this.title = title;
    this.content = content;
    this.notice = notice;
  }

  public void edit(Category category, String title, String content, boolean notice) {
    this.category = category;
    this.title = title;
    this.content = content;
    this.notice = notice;
  }

  public void delete(User by, LocalDateTime now) {
    this.status = PostStatus.DELETED;
    this.deletedBy = by;
    this.deletedAt = now;
  }

  public boolean isPublished() {
    return status == PostStatus.PUBLISHED;
  }

  public boolean isWrittenBy(Long userId) {
    return author.getId().equals(userId);
  }

  public Long getId() {
    return id;
  }

  public Blog getBlog() {
    return blog;
  }

  public User getAuthor() {
    return author;
  }

  public Category getCategory() {
    return category;
  }

  public String getTitle() {
    return title;
  }

  public String getContent() {
    return content;
  }

  public boolean isNotice() {
    return notice;
  }

  public PostStatus getStatus() {
    return status;
  }

  public boolean isAuthorHidden() {
    return authorHidden;
  }

  public int getViewCount() {
    return viewCount;
  }

  public int getLikeCount() {
    return likeCount;
  }

  public int getCommentCount() {
    return commentCount;
  }

  public User getDeletedBy() {
    return deletedBy;
  }

  public LocalDateTime getDeletedAt() {
    return deletedAt;
  }
}
