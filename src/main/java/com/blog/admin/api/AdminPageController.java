package com.blog.admin.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 관리자 화면 주소 {@code /admin}, {@code /admin/}을 정적 화면으로 넘긴다 (T126). ADMIN만 열리는 것은 SecurityConfig가 본다.
 */
@Controller
public class AdminPageController {

  @GetMapping({"/admin", "/admin/"})
  public String index() {
    return "forward:/admin/index.html";
  }
}
