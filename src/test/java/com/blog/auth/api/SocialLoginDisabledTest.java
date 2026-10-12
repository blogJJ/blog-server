package com.blog.auth.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blog.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** 소셜 로그인 키가 하나도 없을 때(로컬·테스트·키를 넣기 전 운영): 서버는 뜨고 버튼과 주소만 없다 (T157, D-128). */
@IntegrationTest
class SocialLoginDisabledTest {

  @Autowired MockMvc mockMvc;

  @Test
  void noProviderMeansNoButtonsAndNoEndpoints() throws Exception {
    mockMvc
        .perform(get("/api/auth/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.socialProviders.length()").value(0));
    mockMvc.perform(get("/oauth2/authorization/google")).andExpect(status().isNotFound());
    mockMvc.perform(get("/login/oauth2/code/google")).andExpect(status().isNotFound());
  }
}
