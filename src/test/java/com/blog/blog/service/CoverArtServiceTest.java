package com.blog.blog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blog.auth.domain.User;
import com.blog.auth.repository.UserRepository;
import com.blog.blog.domain.Blog;
import com.blog.blog.domain.BlogVisibility;
import com.blog.blog.domain.JoinPolicy;
import com.blog.board.service.ImageService;
import com.blog.common.error.BusinessException;
import com.blog.common.storage.FileStorage;
import com.blog.support.IntegrationTest;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 새 블로그의 대표 그림 (T155, BLG-15, D-126). Claude는 가짜로 바꿔 진짜 API를 부르지 않는다. */
@IntegrationTest
class CoverArtServiceTest {

  private static final String DRAWN =
      "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1200 500\" onload=\"alert(1)\">"
          + "<script>alert(1)</script>"
          + "<rect width=\"1200\" height=\"500\" fill=\"#fff0f1\"/>"
          + "<circle cx=\"600\" cy=\"250\" r=\"120\" fill=\"#f04452\"/></svg>";

  @MockitoBean ClaudeCoverArtClient claude;
  @Autowired CoverArtService coverArtService;
  @Autowired BlogService blogService;
  @Autowired ImageService imageService;
  @Autowired FileStorage storage;
  @Autowired UserRepository userRepository;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mockMvc;

  @BeforeEach
  void enableFakeClaude() {
    when(claude.enabled()).thenReturn(true);
  }

  @Test
  void newBlogGetsSanitizedSvgCoverServedAsImage() throws Exception {
    when(claude.draw(eq("민준의 식탁"), anyList())).thenReturn(Optional.of(DRAWN));
    Blog blog = newBlog("민준의 식탁", List.of("집밥", "레시피"));

    String stored = coverArtService.generate(blog.getId()).orElseThrow();

    verify(claude).draw("민준의 식탁", List.of("집밥", "레시피"));
    assertThat(stored).endsWith(".svg");
    assertThat(coverOf(blog)).isEqualTo(stored);
    String saved;
    try (InputStream in = storage.open(stored)) {
      saved = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(saved)
        .startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\"")
        .contains("<circle cx=\"600\" cy=\"250\" fill=\"#f04452\" r=\"120\"/>")
        .doesNotContain("script")
        .doesNotContain("onload");

    mockMvc
        .perform(get(ImageService.url(stored)))
        .andExpect(status().isOk())
        .andExpect(content().contentType("image/svg+xml"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(
            header()
                .string(
                    "Content-Security-Policy",
                    "default-src 'none'; style-src 'unsafe-inline'; sandbox"));
  }

  @Test
  void ownerUploadedCoverIsNotOverwritten() {
    Blog blog = newBlog("사진 일기", List.of("사진"));
    String uploaded = UUID.randomUUID() + ".png";
    jdbc.update("update blogs set cover_image = ? where id = ?", uploaded, blog.getId());

    assertThat(coverArtService.generate(blog.getId())).isEmpty();

    verify(claude, never()).draw(anyString(), anyList());
    assertThat(coverOf(blog)).isEqualTo(uploaded);
  }

  @Test
  void ownerUploadWhileDrawingWinsAndDrawnFileIsDeleted() {
    Blog blog = newBlog("느린 그림", List.of());
    String uploaded = UUID.randomUUID() + ".png";
    when(claude.draw(anyString(), anyList()))
        .thenAnswer(
            inv -> {
              // 그리는 동안 블로그장이 이미지를 올렸다
              jdbc.update("update blogs set cover_image = ? where id = ?", uploaded, blog.getId());
              return Optional.of(DRAWN);
            });

    assertThat(coverArtService.generate(blog.getId())).isEmpty();
    assertThat(coverOf(blog)).isEqualTo(uploaded);
  }

  @Test
  void failuresKeepPixelScene() {
    Blog refused = newBlog("거절", List.of());
    when(claude.draw(eq("거절"), anyList())).thenReturn(Optional.empty());
    assertThat(coverArtService.generate(refused.getId())).isEmpty();
    assertThat(coverOf(refused)).isNull();

    Blog broken = newBlog("깨진 그림", List.of());
    when(claude.draw(eq("깨진 그림"), anyList()))
        .thenReturn(Optional.of("<svg xmlns=\"http://www.w3.org/2000/svg\"><rect"));
    assertThat(coverArtService.generate(broken.getId())).isEmpty();
    assertThat(coverOf(broken)).isNull();

    Blog thrown = newBlog("터짐", List.of());
    when(claude.draw(eq("터짐"), anyList())).thenThrow(new IllegalStateException("boom"));
    coverArtService.generateSafely(thrown.getId()); // 예외가 밖으로 나오지 않는다
    assertThat(coverOf(thrown)).isNull();
  }

  @Test
  void withoutApiKeyNothingIsCalled() {
    when(claude.enabled()).thenReturn(false);
    Blog blog = newBlog("키 없음", List.of());

    assertThat(coverArtService.generate(blog.getId())).isEmpty();
    verify(claude, never()).draw(anyString(), any());
  }

  @Test
  void ownerCanReplaceOrDeleteDrawnCoverAsUsual() {
    when(claude.draw(anyString(), anyList())).thenReturn(Optional.of(DRAWN));
    Blog blog = newBlog("바꿀 그림", List.of());
    String drawn = coverArtService.generate(blog.getId()).orElseThrow();

    // 블로그장이 새 이미지를 올리면 그린 그림 이름을 돌려받아 지운다 (BlogController와 같은 순서)
    String old = blogService.changeCover(blog.getId(), UUID.randomUUID() + ".png");
    assertThat(old).isEqualTo(drawn);
    imageService.deleteQuietly(old);
    assertThat(storage.exists(drawn)).isFalse();

    // 지우면 비어서 픽셀 장면으로 돌아간다
    blogService.changeCover(blog.getId(), null);
    assertThat(coverOf(blog)).isNull();
  }

  @Test
  void svgUploadIsStillRefused() {
    MockMultipartFile svg =
        new MockMultipartFile(
            "file", "cover.svg", "image/svg+xml", DRAWN.getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> imageService.storeCover(svg)).isInstanceOf(BusinessException.class);
  }

  private String coverOf(Blog blog) {
    return jdbc.queryForObject(
        "select cover_image from blogs where id = ?", String.class, blog.getId());
  }

  private Blog newBlog(String name, List<String> tags) {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    User owner =
        userRepository.save(
            new User(
                "c" + unique + "@example.com",
                "hash",
                "이름",
                "c" + unique,
                "01012345678",
                LocalDateTime.now()));
    return blogService.create(
        owner.getId(),
        "c-" + unique,
        new BlogService.BlogForm(name, "소개", BlogVisibility.PUBLIC, JoinPolicy.OPEN, tags));
  }
}
