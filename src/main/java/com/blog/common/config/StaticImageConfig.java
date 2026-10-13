package com.blog.common.config;

import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 정적 그림 {@code /img/**}(공유 미리보기 기본 그림 등, T161)은 배포마다 바뀌지 않으니 7일 캐시한다. HTML·JS·CSS는 배포 뒤 바로 바뀌어야 해서
 * 그대로 둔다.
 */
@Configuration
public class StaticImageConfig implements WebMvcConfigurer {

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler("/img/**")
        .addResourceLocations("classpath:/static/img/")
        .setCacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic());
  }
}
