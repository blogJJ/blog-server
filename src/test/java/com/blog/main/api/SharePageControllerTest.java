package com.blog.main.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.blog.common.web.SiteUrl;
import com.blog.main.service.ShareMetaService;
import com.blog.main.service.ShareMetaService.ShareMeta;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** 공유 미리보기 주소는 사이트 주소(운영은 https)로 만든다 (T160, T161). */
class SharePageControllerTest {

  @Test
  void ogUrlsUseConfiguredHttpsSiteEvenForPlainHttpRequest() {
    ShareMetaService metaService = mock(ShareMetaService.class);
    when(metaService.post("a", 7L, null))
        .thenReturn(
            new ShareMeta(
                "제목", "제목 - 블로그", "설명", "/api/images/1", false, "article", "/blog/a/posts/7"));
    SharePageController controller =
        new SharePageController(metaService, new SiteUrl("https://jj-blog.java21.net", true));
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/blog/a/posts/7");
    request.setScheme("http");
    request.setServerName("10.0.0.1");
    request.setServerPort(8080);

    String html = controller.post("a", 7L, null, request).getBody();

    assertThat(html)
        .contains("og:url\" content=\"https://jj-blog.java21.net/blog/a/posts/7\"")
        .contains("og:image\" content=\"https://jj-blog.java21.net/api/images/1\"")
        .doesNotContain("content=\"http://");
  }
}
