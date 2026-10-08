package com.blog.common.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param secret 해시에 섞는 서버 비밀값(HASH_SECRET). 32바이트 이상
 */
@ConfigurationProperties("app.hash")
public record HashProperties(String secret) {}
