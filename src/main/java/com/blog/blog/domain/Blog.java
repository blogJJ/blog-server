package com.blog.blog.domain;

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

/** 블로그 (blogs). 블로그장은 blog_members의 OWNER 행과 같은 사람이다. */
@Entity
@Table(name = "blogs")
public class Blog extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "owner_id", nullable = false)
  private User owner;

  /** 주소 /blog/{slug}. 영문 소문자·숫자·- 3~30자. 폐쇄 30일 뒤 NULL로 비운다 (D-86). */
  @Column(length = 30, unique = true)
  private String slug;

  /** 이름은 중복을 허용한다. */
  @Column(nullable = false, length = 50)
  private String name;

  @Column(length = 500)
  private String description;

  @Column(name = "cover_image", length = 255)
  private String coverImage;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private BlogVisibility visibility = BlogVisibility.PUBLIC;

  /** 일부 공개 공유 링크의 무작위 값. 새로 만들면 이전 값은 무효 (D-37). */
  @Column(name = "share_key", length = 64, unique = true)
  private String shareKey;

  @Enumerated(EnumType.STRING)
  @Column(name = "join_policy", nullable = false)
  private JoinPolicy joinPolicy = JoinPolicy.OPEN;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private BlogStatus status = BlogStatus.ACTIVE;

  /** 관리자가 숨김 (ADM-02). */
  @Column(name = "is_hidden", nullable = false)
  private boolean hidden;

  @Column(name = "close_scheduled_at")
  private LocalDateTime closeScheduledAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "close_reason")
  private CloseReason closeReason;

  @Column(name = "closed_at")
  private LocalDateTime closedAt;

  /** 인기순 정렬용 (D-89). 블로그장 한 명으로 시작한다. */
  @Column(name = "member_count", nullable = false)
  private int memberCount = 1;

  @Column(name = "post_count", nullable = false)
  private int postCount;

  @Column(name = "subscriber_count", nullable = false)
  private int subscriberCount;

  protected Blog() {}

  public Blog(
      User owner,
      String slug,
      String name,
      String description,
      BlogVisibility visibility,
      JoinPolicy joinPolicy) {
    this.owner = owner;
    this.slug = slug;
    this.name = name;
    this.description = description;
    this.visibility = visibility;
    this.joinPolicy = joinPolicy;
  }

  public boolean isActive() {
    return status == BlogStatus.ACTIVE;
  }

  public Long getId() {
    return id;
  }

  public User getOwner() {
    return owner;
  }

  public String getSlug() {
    return slug;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public String getCoverImage() {
    return coverImage;
  }

  public BlogVisibility getVisibility() {
    return visibility;
  }

  public String getShareKey() {
    return shareKey;
  }

  public JoinPolicy getJoinPolicy() {
    return joinPolicy;
  }

  public BlogStatus getStatus() {
    return status;
  }

  public boolean isHidden() {
    return hidden;
  }

  public LocalDateTime getCloseScheduledAt() {
    return closeScheduledAt;
  }

  public CloseReason getCloseReason() {
    return closeReason;
  }

  public LocalDateTime getClosedAt() {
    return closedAt;
  }

  public int getMemberCount() {
    return memberCount;
  }

  public int getPostCount() {
    return postCount;
  }

  public int getSubscriberCount() {
    return subscriberCount;
  }
}
