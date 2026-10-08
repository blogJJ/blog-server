package com.blog.social.domain;

/** 신고를 처리할 사람 (reports.handler_scope, D-45, D-94, D-114). BLOG_OWNER는 블로그장과 멤버 관리 권한 부블로그장. */
public enum HandlerScope {
  BLOG_OWNER,
  ADMIN
}
