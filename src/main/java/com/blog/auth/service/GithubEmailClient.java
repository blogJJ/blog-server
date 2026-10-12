package com.blog.auth.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 깃허브 계정의 대표(primary) 이메일 중 인증된 것을 읽는다 (T157, D-128). 이메일을 공개하지 않은 계정은 /user에 이메일이 없고, 있어도 인증 여부를 알
 * 수 없어서 user:email 권한으로 /user/emails를 부른다.
 *
 * <p>깃허브에 닿지 못하거나 답이 이상하면 이메일이 없는 것으로 본다(연결하지 않는다). 토큰은 로그에 남기지 않는다.
 */
@Component
public class GithubEmailClient {

  static final String EMAILS_URL = "https://api.github.com/user/emails";
  private static final Logger log = LoggerFactory.getLogger(GithubEmailClient.class);

  private final RestClient restClient;

  public GithubEmailClient() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(3));
    factory.setReadTimeout(Duration.ofSeconds(3));
    this.restClient = RestClient.builder().requestFactory(factory).build();
  }

  /**
   * @return 대표 이메일이 인증돼 있으면 그 주소, 아니면 null
   */
  public String primaryVerifiedEmail(String accessToken) {
    try {
      List<Map<String, Object>> emails =
          restClient
              .get()
              .uri(EMAILS_URL)
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
              .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
              .retrieve()
              .body(new ParameterizedTypeReference<>() {});
      return pickPrimaryVerified(emails);
    } catch (RestClientException e) {
      log.warn("github emails failed: {}", e.getClass().getSimpleName());
      return null;
    }
  }

  static String pickPrimaryVerified(List<Map<String, Object>> emails) {
    if (emails == null) {
      return null;
    }
    for (Map<String, Object> e : emails) {
      if (Boolean.TRUE.equals(e.get("primary"))
          && Boolean.TRUE.equals(e.get("verified"))
          && e.get("email") instanceof String email) {
        return email;
      }
    }
    return null;
  }
}
