package com.blog.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 서버가 만드는 절대 주소의 앞부분(사이트 주소) (T160). 공유 미리보기(og:url, og:image)와 소셜 로그인 돌아오는 주소가 쓴다.
 *
 * <p>{@code app.site-url}이 있으면 그 값을 쓰고(prod는 https://jj-blog.java21.net), 비어 있으면(local·test) 요청
 * 주소에서 만든다. 운영에서는 프록시 헤더가 빠지거나 바뀌어도 http 주소가 나가지 않게 고정 값을 쓴다. {@code
 * app.security.require-https=true}인데 https가 아닌 값이면 서버가 뜨지 않는다.
 */
@Component
public class SiteUrl {

  private final String configured;

  public SiteUrl(
      @Value("${app.site-url:}") String siteUrl,
      @Value("${app.security.require-https:false}") boolean requireHttps) {
    this.configured = normalize(siteUrl);
    if (requireHttps && (configured == null || !configured.startsWith("https://"))) {
      throw new IllegalStateException(
          "app.site-url must be an https:// address when https is required");
    }
  }

  /** 정해 둔 사이트 주소(끝의 / 없이). 없으면 null */
  public String configured() {
    return configured;
  }

  /** 사이트 주소. 정해 둔 값이 없으면 요청의 scheme·host·port로 만든다 */
  public String origin(HttpServletRequest request) {
    if (configured != null) {
      return configured;
    }
    return UriComponentsBuilder.fromUriString(request.getRequestURL().toString())
        .replacePath(null)
        .replaceQuery(null)
        .build()
        .toUriString();
  }

  static String normalize(String siteUrl) {
    if (siteUrl == null || siteUrl.isBlank()) {
      return null;
    }
    String s = siteUrl.strip();
    while (s.endsWith("/")) {
      s = s.substring(0, s.length() - 1);
    }
    return s;
  }
}
