package com.blog.board.domain;

import com.blog.blog.domain.Blog;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;

/** 블로그 카테고리 (categories, T060, BRD-03). 블로그 안에서 이름이 겹치지 않는다. */
@Entity
@Table(
    name = "categories",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_categories",
            columnNames = {"blog_id", "name"}))
public class Category {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "blog_id", nullable = false)
  private Blog blog;

  @Column(nullable = false, length = 30)
  private String name;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  protected Category() {}

  public Category(Blog blog, String name, int sortOrder) {
    this.blog = blog;
    this.name = name;
    this.sortOrder = sortOrder;
  }

  public void rename(String name) {
    this.name = name;
  }

  public void changeSortOrder(int sortOrder) {
    this.sortOrder = sortOrder;
  }

  public Long getId() {
    return id;
  }

  public Blog getBlog() {
    return blog;
  }

  public String getName() {
    return name;
  }

  public int getSortOrder() {
    return sortOrder;
  }
}
