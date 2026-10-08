package com.blog.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;

/**
 * 화면 주소에서 난 오류를 static/error의 안내 화면으로 보여 준다 (T136, OPS-02, D-102). 403(401 포함)은 403.html, 404는
 * 404.html, 5xx는 500.html에 오류 번호를 넣어 보여 준다. 화면에는 내부 정보를 넣지 않는다.
 *
 * <p>API(/api/**) 오류는 여기까지 오지 않고 GlobalExceptionHandler가 JSON으로 답한다.
 */
@Component
public class ErrorPageViewResolver implements ErrorViewResolver, Ordered {

  /** 500.html에서 오류 번호로 바꿀 자리 */
  static final String ERROR_ID_PLACEHOLDER = "<span data-error-id>-</span>";

  private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{1,36}");

  @Override
  public ModelAndView resolveErrorView(
      HttpServletRequest request, HttpStatus status, Map<String, Object> model) {
    String page;
    if (status.is5xxServerError()) {
      page = "500";
    } else if (status == HttpStatus.NOT_FOUND) {
      page = "404";
    } else if (status == HttpStatus.FORBIDDEN || status == HttpStatus.UNAUTHORIZED) {
      page = "403";
    } else {
      return null;
    }
    String html = load(page);
    if (page.equals("500")) {
      Object errorId = request.getAttribute(GlobalExceptionHandler.ERROR_ID_ATTRIBUTE);
      if (errorId instanceof String id && SAFE_ID.matcher(id).matches()) {
        html = html.replace(ERROR_ID_PLACEHOLDER, "<span data-error-id>" + id + "</span>");
      }
    }
    ModelAndView view = new ModelAndView(new HtmlView(html));
    view.setStatus(status);
    return view;
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  private static String load(String page) {
    try (InputStream in =
        new ClassPathResource("static/error/" + page + ".html").getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Missing error page " + page, e);
    }
  }

  private record HtmlView(String html) implements View {

    @Override
    public String getContentType() {
      return MediaType.TEXT_HTML_VALUE + ";charset=UTF-8";
    }

    @Override
    public void render(
        Map<String, ?> model, HttpServletRequest request, HttpServletResponse response)
        throws IOException {
      response.setContentType(getContentType());
      response.getWriter().write(html);
    }
  }
}
