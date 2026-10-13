package com.blog.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** 사이트 주소 (T160) */
class SiteUrlTest {

  @Test
  void configuredSiteUrlWinsOverRequest() {
    SiteUrl site = new SiteUrl("https://jj-blog.java21.net/", true);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/blog/a/posts/1");
    request.setScheme("http");
    request.setServerName("10.0.0.1");
    request.setServerPort(8080);

    assertThat(site.origin(request)).isEqualTo("https://jj-blog.java21.net");
  }

  @Test
  void emptySiteUrlFallsBackToRequest() {
    SiteUrl site = new SiteUrl("", false);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/blog/a/posts/1");
    request.setServerPort(8080);

    assertThat(site.configured()).isNull();
    assertThat(site.origin(request)).isEqualTo("http://localhost:8080");
  }

  @Test
  void httpsRequiredRejectsHttpOrEmptySiteUrl() {
    assertThatThrownBy(() -> new SiteUrl("http://jj-blog.java21.net", true))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new SiteUrl("", true)).isInstanceOf(IllegalStateException.class);
  }
}
