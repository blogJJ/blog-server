package com.blog.blog.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 블로그 주소 {@code /blog/{주소}}를 정적 화면으로 넘긴다 (D-70). 화면의 JS가 주소를 읽어 API를 부른다. */
@Controller
public class BlogPageController {

  @GetMapping("/blog/{slug:[a-z0-9-]+}")
  public String blog() {
    return "forward:/blog.html";
  }

  @GetMapping("/blog/{slug:[a-z0-9-]+}/admin")
  public String admin() {
    return "forward:/blog-admin.html";
  }
}
