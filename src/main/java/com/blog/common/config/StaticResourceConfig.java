package com.blog.common.config;

import com.blog.common.web.AssetVersions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.ResourceTransformer;
import org.springframework.web.servlet.resource.ResourceTransformerChain;
import org.springframework.web.servlet.resource.TransformedResource;

/**
 * 정적 파일의 캐시 (T164, T161).
 *
 * <ul>
 *   <li>그림 {@code /img/**}(공유 미리보기 기본 그림 등)은 배포마다 바뀌지 않으니 7일 캐시한다.
 *   <li>JS·CSS({@code /js, /css, /vendor})는 화면 HTML이 {@code ?v=내용해시}를 붙여 부른다({@link
 *       AssetVersions}). 해시가 지금 파일과 같으면 1년 동안 다시 묻지 않게(immutable) 두고, 버전 없이 부르면(JS가 나중에 불러오는 파일 등)
 *       매번 ETag로 바뀌었는지만 묻는다. 예전에는 Spring Security 기본값(no-store)이라 화면을 옮길 때마다 pixel-cover.js(약
 *       390KB) 등을 처음부터 다시 받았다.
 *   <li>화면 HTML({@code /*.html})은 배포 뒤 바로 바뀌어야 해서 매번 받는다(no-cache). 보낼 때 JS·CSS 주소에 버전을 붙인다. 공유
 *       미리보기 태그가 붙는 메인·블로그·글 화면은 {@link com.blog.main.api.SharePageController}가 같은 일을 한다.
 *   <li>화면 HTML에는 {@link #SPECULATION_RULES} 헤더를 붙여, 블로그·글 링크에 마우스를 올리거나 누르기 시작하면 브라우저가 그 화면 HTML을
 *       미리 받게 한다(Chrome 계열만, 다른 브라우저는 무시).
 * </ul>
 */
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

  /** 버전이 맞는 JS·CSS. 주소가 내용마다 달라서 오래 둬도 된다. */
  static final String IMMUTABLE = "max-age=31536000, public, immutable";

  /** 화면 HTML에 붙이는 미리 받기 규칙 헤더(Speculation Rules). 규칙 파일은 SharePageController가 준다. */
  public static final String SPECULATION_RULES_HEADER = "Speculation-Rules";

  public static final String SPECULATION_RULES = "\"/speculation-rules.json\"";

  private static final String[] ASSET_PATHS = {"/js/**", "/css/**", "/vendor/**"};
  private static final String[] PAGE_PATHS = {"/*.html", "/admin/*.html"};

  private final AssetVersions versions;

  public StaticResourceConfig(AssetVersions versions) {
    this.versions = versions;
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler("/img/**")
        .addResourceLocations("classpath:/static/img/")
        .setCacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic());

    // Cache-Control은 아래 AssetCacheInterceptor가 ?v= 값을 보고 정한다
    for (String dir : new String[] {"js", "css", "vendor"}) {
      registry
          .addResourceHandler("/" + dir + "/**")
          .addResourceLocations("classpath:/static/" + dir + "/")
          .setUseLastModified(false)
          .setEtagGenerator(versions::etag);
    }

    HtmlAssetTransformer html = new HtmlAssetTransformer(versions);
    registry
        .addResourceHandler("/*.html")
        .addResourceLocations("classpath:/static/")
        .setUseLastModified(false)
        .setCacheControl(CacheControl.noCache())
        .resourceChain(true)
        .addTransformer(html);
    registry
        .addResourceHandler("/admin/*.html")
        .addResourceLocations("classpath:/static/admin/")
        .setUseLastModified(false)
        .setCacheControl(CacheControl.noCache())
        .resourceChain(true)
        .addTransformer(html);
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new AssetCacheInterceptor(versions)).addPathPatterns(ASSET_PATHS);
    registry.addInterceptor(new PageHeaderInterceptor()).addPathPatterns(PAGE_PATHS);
  }

  /** 지금 파일의 해시와 같은 {@code ?v=}로 부르면 오래 캐시하고, 아니면 매번 묻게 한다. */
  static final class AssetCacheInterceptor implements HandlerInterceptor {

    private final AssetVersions versions;

    AssetCacheInterceptor(AssetVersions versions) {
      this.versions = versions;
    }

    @Override
    public boolean preHandle(
        HttpServletRequest request, HttpServletResponse response, Object handler) {
      String v = request.getParameter("v");
      String path = request.getRequestURI().substring(request.getContextPath().length());
      boolean current = v != null && versions.version(path).map(v::equals).orElse(false);
      response.setHeader(
          HttpHeaders.CACHE_CONTROL, current ? IMMUTABLE : CacheControl.noCache().getHeaderValue());
      return true;
    }
  }

  static final class PageHeaderInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(
        HttpServletRequest request, HttpServletResponse response, Object handler) {
      response.setHeader(SPECULATION_RULES_HEADER, SPECULATION_RULES);
      return true;
    }
  }

  /** 화면 HTML 안의 JS·CSS 주소에 버전을 붙인다. 결과는 resourceChain의 캐시에 남아 한 번만 바꾼다. */
  static final class HtmlAssetTransformer implements ResourceTransformer {

    private final AssetVersions versions;

    HtmlAssetTransformer(AssetVersions versions) {
      this.versions = versions;
    }

    @Override
    public Resource transform(
        HttpServletRequest request, Resource resource, ResourceTransformerChain chain)
        throws IOException {
      Resource r = chain.transform(request, resource);
      String name = r.getFilename();
      if (name == null || !name.endsWith(".html")) {
        return r;
      }
      String html = new String(r.getContentAsByteArray(), StandardCharsets.UTF_8);
      return new TransformedResource(r, versions.rewrite(html).getBytes(StandardCharsets.UTF_8));
    }
  }
}
