package com.blog.blog.domain;

import com.blog.auth.domain.User;
import com.blog.common.domain.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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

  /** 태그로 고른 픽셀 동물. null이면 아직 안 고름 (BLG-14, D-116) */
  @Convert(converter = MascotConverter.class)
  @Column(length = 20)
  private Mascot mascot;

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

  /** 폐쇄되지 않았다. 폐쇄 예정(CLOSING)은 평소처럼 운영한다 (D-67). */
  public boolean isOpen() {
    return status != BlogStatus.CLOSED;
  }

  /** 블로그 정보 수정 (T053). 주소(slug)는 바꾸지 않는다. */
  public void updateInfo(
      String name, String description, BlogVisibility visibility, JoinPolicy joinPolicy) {
    this.name = name;
    this.description = description;
    this.visibility = visibility;
    this.joinPolicy = joinPolicy;
  }

  /** 공유 링크 값을 바꾼다. null이면 링크를 없앤다 (D-37, D-49). */
  public void changeShareKey(String shareKey) {
    this.shareKey = shareKey;
  }

  public void changeCoverImage(String coverImage) {
    this.coverImage = coverImage;
  }

  public void changeMascot(Mascot mascot) {
    this.mascot = mascot;
  }

  /** 폐쇄 예정으로 바꾼다 (BLG-09, D-12). 예정 시각은 서비스가 정한다(누른 날짜 + 7일 04:00). */
  public void scheduleClose(LocalDateTime at, CloseReason reason) {
    this.status = BlogStatus.CLOSING;
    this.closeScheduledAt = at;
    this.closeReason = reason;
  }

  /** 폐쇄 철회 (D-19). 운영 중으로 돌아간다. */
  public void revokeClose() {
    this.status = BlogStatus.ACTIVE;
    this.closeScheduledAt = null;
    this.closeReason = null;
  }

  /** 04:00 배치가 폐쇄한다. 30일 뒤 내용 삭제의 기준 시각을 남긴다 (D-86). */
  public void markClosed(LocalDateTime now) {
    this.status = BlogStatus.CLOSED;
    this.closedAt = now;
  }

  /** 관리자 숨김 (ADM-02). 숨긴 블로그는 없는 블로그처럼 보인다. */
  public void hide() {
    this.hidden = true;
  }

  public void unhide() {
    this.hidden = false;
  }

  /** 블로그장을 바꾼다: 위임 수락(BLG-08), 블로그장 박탈 뒤 자동 위임(ADM-07). blog_members의 OWNER 행도 같이 바꿔야 한다. */
  public void changeOwner(User newOwner) {
    this.owner = newOwner;
  }

  /** 폐쇄 30일 뒤 주소를 비워 다른 사람이 쓸 수 있게 한다 (D-86). */
  public void clearSlug() {
    this.slug = null;
  }

  public boolean isClosing() {
    return status == BlogStatus.CLOSING;
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

  public Mascot getMascot() {
    return mascot;
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
