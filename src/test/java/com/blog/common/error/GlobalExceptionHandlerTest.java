package com.blog.common.error;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new TestController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void businessExceptionBecomesCodeAndMessage() throws Exception {
    mockMvc
        .perform(get("/test/business"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("블로그를 찾을 수 없어요."))
        .andExpect(jsonPath("$.errorId").doesNotExist());
  }

  @Test
  void validationFailureReturnsFieldMessage() throws Exception {
    mockMvc
        .perform(
            post("/test/valid").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        .andExpect(jsonPath("$.message").value("이름을 입력해 주세요."));
  }

  @Test
  void malformedJsonIsBadRequestWithoutParserDetails() throws Exception {
    mockMvc
        .perform(post("/test/valid").contentType(MediaType.APPLICATION_JSON).content("{oops"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_INPUT.getDefaultMessage()));
  }

  @Test
  void unexpectedErrorHidesInternalsAndGivesErrorId() throws Exception {
    mockMvc
        .perform(get("/test/boom"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.errorId").isNotEmpty())
        .andExpect(content().string(not(containsString("SELECT"))))
        .andExpect(content().string(not(containsString("IllegalStateException"))));
  }

  @Test
  void timeoutAsksToRetry() throws Exception {
    for (String path : new String[] {"/test/query-timeout", "/test/async-timeout"}) {
      mockMvc
          .perform(get(path))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("REQUEST_TIMEOUT"));
    }
  }

  @RestController
  static class TestController {

    @GetMapping("/test/query-timeout")
    void queryTimeout() {
      throw new org.springframework.dao.QueryTimeoutException("Statement cancelled");
    }

    @GetMapping("/test/async-timeout")
    void asyncTimeout() {
      throw new org.springframework.web.context.request.async.AsyncRequestTimeoutException();
    }

    @GetMapping("/test/business")
    void business() {
      throw new BusinessException(ErrorCode.NOT_FOUND, "블로그를 찾을 수 없어요.");
    }

    @PostMapping("/test/valid")
    void valid(@Valid @RequestBody NameRequest request) {}

    @GetMapping("/test/boom")
    void boom() {
      throw new IllegalStateException("SELECT * FROM users failed at /srv/app");
    }
  }

  record NameRequest(@NotBlank(message = "이름을 입력해 주세요.") String name) {}
}
