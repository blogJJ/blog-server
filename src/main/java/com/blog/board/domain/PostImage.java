package com.blog.board.domain;

import com.blog.auth.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 글 이미지 (post_images, T058, BRD-05). 글을 저장하기 전에 올리므로 처음에는 post가 비어 있고, 글을 저장할 때 본문에 들어 있는 이미지만
 * 연결한다. 원래 파일 이름은 DB에만 두고 화면에는 내보내지 않는다.
 */
@Entity
@Table(name = "post_images")
public class PostImage {

  public static final int MAX_BYTES = 3 * 1024 * 1024;
  public static final int MAX_PER_POST = 10;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "post_id")
  private Post post;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "uploader_id", nullable = false)
  private User uploader;

  @Column(name = "stored_name", nullable = false, length = 64, unique = true)
  private String storedName;

  @Column(name = "original_name", nullable = false, length = 255)
  private String originalName;

  @Column(name = "content_type", nullable = false, length = 30)
  private String contentType;

  @Column(name = "size_bytes", nullable = false)
  private int sizeBytes;

  @JdbcTypeCode(SqlTypes.TINYINT)
  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "deleted_at")
  private LocalDateTime deletedAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  protected PostImage() {}

  public PostImage(
      User uploader, String storedName, String originalName, String contentType, int sizeBytes) {
    this.uploader = uploader;
    this.storedName = storedName;
    this.originalName = originalName;
    this.contentType = contentType;
    this.sizeBytes = sizeBytes;
  }

  public void attach(Post post, int sortOrder) {
    this.post = post;
    this.sortOrder = sortOrder;
  }

  /** 본문에서 빠진 이미지. 연결을 끊어 두면 정리 배치가 지운다. */
  public void detach() {
    this.post = null;
    this.sortOrder = 0;
  }

  public Long getId() {
    return id;
  }

  public Post getPost() {
    return post;
  }

  public User getUploader() {
    return uploader;
  }

  public String getStoredName() {
    return storedName;
  }

  public String getOriginalName() {
    return originalName;
  }

  public String getContentType() {
    return contentType;
  }

  public int getSizeBytes() {
    return sizeBytes;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
