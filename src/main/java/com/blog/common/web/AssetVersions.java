package com.blog.common.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

/**
 * 정적 JS·CSS 주소에 붙이는 버전 값 (T164). 화면 HTML의 {@code src="/js/api.js"}를 {@code
 * src="/js/api.js?v=내용해시"}로 바꿔 두면, 브라우저는 그 주소를 1년 동안 다시 받지 않고, 파일이 바뀌어 배포되면 해시가 달라져 새 주소로 바로 받는다.
 *
 * <p>해시는 jar 안 파일 내용으로 만든다. jar의 파일 시각은 빌드마다 같아서(1980-02-01) Last-Modified로는 바뀐 것을 알 수 없다. 같은 해시를
 * 버전 없이 부른 JS·CSS의 ETag로도 쓴다.
 */
@Component
public class AssetVersions {

  /** 화면 HTML에서 버전을 붙일 주소: /js, /css, /vendor 아래 파일. 이미 ?가 있으면 그대로 둔다. */
  private static final Pattern ASSET =
      Pattern.compile("(\\s(?:src|href)=\")(/(?:js|css|vendor)/[A-Za-z0-9._/-]+)(\")");

  private static final int LENGTH = 12;

  private final Map<String, Optional<String>> byPath = new ConcurrentHashMap<>();
  private final Map<String, String> byResource = new ConcurrentHashMap<>();

  /** {@code static} 아래 경로(예: /js/api.js)의 버전. 파일이 없으면 빈 값. */
  public Optional<String> version(String path) {
    if (path == null || path.contains("..")) {
      return Optional.empty();
    }
    return byPath.computeIfAbsent(
        path,
        p -> {
          ClassPathResource resource = new ClassPathResource("static" + p);
          return resource.isReadable() ? Optional.of(hash(resource)) : Optional.empty();
        });
  }

  /** 화면 HTML 안의 JS·CSS 주소에 {@code ?v=버전}을 붙인다. 없는 파일은 그대로 둔다. */
  public String rewrite(String html) {
    Matcher m = ASSET.matcher(html);
    StringBuilder sb = new StringBuilder(html.length() + 512);
    while (m.find()) {
      String path = m.group(2);
      String replacement =
          version(path).map(v -> m.group(1) + path + "?v=" + v + m.group(3)).orElse(m.group());
      m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  /**
   * 정적 파일의 ETag 값. 약한 ETag(W/)로 준다: Tomcat은 강한 ETag가 붙은 응답을 gzip으로 줄이지 않는다. 읽을 수 없으면 null이라 ETag를
   * 붙이지 않는다.
   */
  public String etag(Resource resource) {
    try {
      String key = resource.getURI().toString();
      return "W/\"" + byResource.computeIfAbsent(key, k -> hash(resource)) + "\"";
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  private static String hash(Resource resource) {
    try (InputStream in = resource.getInputStream()) {
      return DigestUtils.md5DigestAsHex(in).substring(0, LENGTH);
    } catch (IOException e) {
      throw new IllegalStateException("cannot read " + resource, e);
    }
  }
}
