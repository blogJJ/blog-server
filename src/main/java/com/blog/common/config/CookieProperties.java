package com.blog.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 로그인 쿠키 설정 (T144, SEC-04, OPS-11).
 *
 * @param secure 쿠키에 Secure를 붙일지. 기본은 true이고 http://localhost에서 쓰는 local 프로필만 끈다
 */
@ConfigurationProperties("app.cookie")
public record CookieProperties(@DefaultValue("true") boolean secure) {}
