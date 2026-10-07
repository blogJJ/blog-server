package com.blog.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 현재 시각은 이 Clock으로 얻는다. 테스트에서 시각을 고정할 수 있다. 시간대는 Asia/Seoul(main에서 지정). */
@Configuration
public class TimeConfig {

  @Bean
  public Clock clock() {
    return Clock.systemDefaultZone();
  }
}
