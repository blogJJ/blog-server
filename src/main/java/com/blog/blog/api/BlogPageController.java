package com.blog.blog.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 블로그 관리 주소 {@code /blog/{주소}/admin}을 정적 화면으로 넘긴다 (D-70). 화면의 JS가 주소를 읽어 API를 부른다. 블로그 첫 화면과 글 화면은
 * 공유 미리보기 태그를 넣는 {@link com.blog.main.api.SharePageController}가 맡는다 (T161).
 */
@Controller
public class BlogPageController {

  @GetMapping("/blog/{slug:[a-z0-9-]+}/admin")
  public String admin() {
    return "forward:/blog-admin.html";
  }
}
