package com.blog.board.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.blog.board.service.PostQueryService;
import com.blog.board.service.PostQueryService.OgInfo;
import com.blog.common.web.SiteUrl;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** 공유 미리보기 주소는 사이트 주소(운영은 https)로 만든다 (T160). */
class PostPageControllerTest {

  @Test
  void ogUrlsUseConfiguredHttpsSiteEvenForPlainHttpRequest() {
    PostQueryService queryService = mock(PostQueryService.class);
    when(queryService.ogInfo(7L, null)).thenReturn(new OgInfo("제목", "설명", "/api/images/1", "블로그"));
    PostPageController controller =
        new PostPageController(queryService, new SiteUrl("https://jj-blog.java21.net", true));
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/blog/a/posts/7");
    request.setScheme("http");
    request.setServerName("10.0.0.1");
    request.setServerPort(8080);
    request.setQueryString("x=1");

    String html = controller.page("a", 7L, null, request).getBody();

    assertThat(html)
        .contains("og:url\" content=\"https://jj-blog.java21.net/blog/a/posts/7?x=1\"")
        .contains("og:image\" content=\"https://jj-blog.java21.net/api/images/1\"")
        .doesNotContain("content=\"http://");
  }
}
