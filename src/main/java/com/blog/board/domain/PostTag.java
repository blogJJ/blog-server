package com.blog.board.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 글-태그 연결 (post_tags, T058, BRD-04). */
@Entity
@Table(
    name = "post_tags",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_post_tags",
            columnNames = {"post_id", "tag_id"}))
public class PostTag {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "post_id", nullable = false)
  private Post post;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "tag_id", nullable = false)
  private Tag tag;

  protected PostTag() {}

  public PostTag(Post post, Tag tag) {
    this.post = post;
    this.tag = tag;
  }

  public Long getId() {
    return id;
  }

  public Post getPost() {
    return post;
  }

  public Tag getTag() {
    return tag;
  }
}
