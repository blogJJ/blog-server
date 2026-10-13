package com.blog.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** 화면과 스크립트가 가리키는 정적 페이지(/xxx.html)가 실제로 있는지 본다. 가입 화면의 이용약관·개인정보 처리방침 링크가 404였던 일을 막는다 (T133). */
class StaticLinksTest {

  private static final Path STATIC = Path.of("src/main/resources/static");
  private static final Pattern PAGE_LINK =
      Pattern.compile("[\"'](/(?:[a-z0-9-]+/)*[a-z0-9-]+\\.html)");

  @Test
  void everyLinkedPageExists() throws IOException {
    Set<String> missing = new TreeSet<>();
    try (Stream<Path> files = Files.walk(STATIC)) {
      for (Path file :
          files
              .filter(p -> p.toString().endsWith(".html") || p.toString().endsWith(".js"))
              .filter(p -> !p.startsWith(STATIC.resolve("vendor")))
              .toList()) {
        Matcher m = PAGE_LINK.matcher(Files.readString(file, StandardCharsets.UTF_8));
        while (m.find()) {
          String page = m.group(1);
          if (!Files.exists(STATIC.resolve(page.substring(1)))) {
            missing.add(STATIC.relativize(file) + " -> " + page);
          }
        }
      }
    }
    assertThat(missing).isEmpty();
  }
}
