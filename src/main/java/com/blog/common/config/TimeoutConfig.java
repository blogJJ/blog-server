package com.blog.common.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 요청 처리 5초 제한 (T009, D-48, SC-001).
 *
 * <ul>
 *   <li>트랜잭션 기본 제한 시간을 5초로 둔다. Spring이 남은 시간을 쿼리마다 JDBC 쿼리 제한 시간으로 넘기므로 느린 쿼리는 DB에서 중단된다. 리포지토리
 *       메서드는 기본으로 트랜잭션 안에서 돌아서 함께 걸린다.
 *   <li>비동기 응답(Callable, DeferredResult)도 5초가 지나면 끊는다.
 * </ul>
 *
 * <p>시간이 넘으면 GlobalExceptionHandler가 REQUEST_TIMEOUT("다시 시도") 응답을 준다. 화면 쪽 5초 중단은 api.js(T027)가
 * 맡는다.
 */
@Configuration
public class TimeoutConfig implements WebMvcConfigurer {

  private final Duration requestTimeout;

  public TimeoutConfig(@Value("${app.request-timeout:5s}") Duration requestTimeout) {
    this.requestTimeout = requestTimeout;
  }

  @Override
  public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
    configurer.setDefaultTimeout(requestTimeout.toMillis());
  }

  /** 트랜잭션 매니저마다 기본 제한 시간(초)을 넣는다. 메서드에 {@code @Transactional(timeout = ...)}을 주면 그 값이 먼저다. */
  @Bean
  static BeanPostProcessor transactionTimeoutPostProcessor(
      @Value("${app.request-timeout:5s}") Duration requestTimeout) {
    int seconds = (int) Math.max(1, requestTimeout.toSeconds());
    return new BeanPostProcessor() {
      @Override
      public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof AbstractPlatformTransactionManager manager) {
          manager.setDefaultTimeout(seconds);
        }
        return bean;
      }
    };
  }
}
