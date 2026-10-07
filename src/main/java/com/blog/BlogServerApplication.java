package com.blog;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BlogServerApplication {

  public static void main(String[] args) {
    // AWS 서버 기본 시간대는 UTC라서, 빠뜨리면 04:00 배치가 한국 시간 오후 1시에 돈다.
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
    SpringApplication.run(BlogServerApplication.class, args);
  }
}
