package com.blog.auth.service;

import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Cloudflare Turnstile 사람 확인을 서버에서 검증한다 (T033, SEC-13, D-79). 화면 위젯이 준 토큰을 Cloudflare siteverify에
 * 보내 확인한다.
 *
 * <p>Cloudflare에 닿지 못하거나 답이 이상하면 통과시키지 않는다(실패로 본다). 키는 TURNSTILE_SITE_KEY·TURNSTILE_SECRET_KEY이고,
 * local 프로필은 Cloudflare가 공개한 항상 통과하는 시험용 키를 기본으로 쓴다.
 */
@Component
public class TurnstileVerifier {

  static final String VERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";
  private static final Logger log = LoggerFactory.getLogger(TurnstileVerifier.class);

  private final RestClient restClient;
  private final String siteKey;
  private final String secretKey;

  public TurnstileVerifier(
      @Value("${app.turnstile.site-key:}") String siteKey,
      @Value("${app.turnstile.secret-key:}") String secretKey) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(3));
    factory.setReadTimeout(Duration.ofSeconds(3));
    this.restClient = RestClient.builder().requestFactory(factory).build();
    this.siteKey = siteKey;
    this.secretKey = secretKey;
  }

  /** 화면에 위젯을 그릴 때 쓰는 공개 키 */
  public String getSiteKey() {
    return siteKey;
  }

  /** 토큰이 사람 확인을 통과했으면 true */
  public boolean verify(String token, String remoteIp) {
    if (token == null || token.isBlank() || secretKey == null || secretKey.isBlank()) {
      return false;
    }
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("secret", secretKey);
    form.add("response", token);
    if (remoteIp != null) {
      form.add("remoteip", remoteIp);
    }
    try {
      Map<?, ?> body =
          restClient
              .post()
              .uri(VERIFY_URL)
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .body(Map.class);
      return body != null && Boolean.TRUE.equals(body.get("success"));
    } catch (RestClientException e) {
      log.warn("turnstile verify failed: {}", e.getClass().getSimpleName());
      return false;
    }
  }
}
