package com.blog.blog.domain;

import com.blog.board.domain.Tag;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 블로그 태그 (blog_tags). 블로그 검색에 쓴다 (T041, BLG-01, BLG-03, D-88). */
@Entity
@Table(
    name = "blog_tags",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_blog_tags",
            columnNames = {"blog_id", "tag_id"}))
public class BlogTag {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "blog_id", nullable = false)
  private Blog blog;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "tag_id", nullable = false)
  private Tag tag;

  protected BlogTag() {}

  public BlogTag(Blog blog, Tag tag) {
    this.blog = blog;
    this.tag = tag;
  }

  public Long getId() {
    return id;
  }

  public Blog getBlog() {
    return blog;
  }

  public Tag getTag() {
    return tag;
  }
}
