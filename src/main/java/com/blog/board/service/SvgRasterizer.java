package com.blog.board.service;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.parser.resources.ResourcePolicy;
import com.github.weisj.jsvg.view.FloatSize;
import com.github.weisj.jsvg.view.ViewBox;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/**
 * 정리한 SVG 대표 그림을 공유 미리보기용 PNG로 바꾼다 (T161). 카카오톡·슬랙·디스코드 같은 미리보기는 SVG를 보여 주지 않아서다.
 *
 * <ul>
 *   <li>그리기는 JSVG(순수 자바, MIT)가 한다. 글자 요소는 {@link SvgSanitizer}가 이미 빼서 글꼴이 없어도 된다.
 *   <li>바깥 파일·주소는 읽지 않는다(DENY_ALL). 정리한 SVG만 넣는다.
 *   <li>결과는 1200x630. 그림 비율이 달라도 가운데를 기준으로 꽉 채우고 넘치는 쪽을 자른다. 빈 곳은 흰 바탕.
 * </ul>
 */
public final class SvgRasterizer {

  public static final int WIDTH = 1200;
  public static final int HEIGHT = 630;

  private static final LoaderContext CONTEXT =
      LoaderContext.builder().externalResourcePolicy(ResourcePolicy.DENY_ALL).build();

  /** {@code url('#id')}처럼 따옴표 친 참조. 브라우저는 읽지만 JSVG는 못 읽어 검게 칠한다. */
  private static final Pattern QUOTED_URL =
      Pattern.compile("url\\(\\s*(['\"])(#[^'\"()]*)\\1\\s*\\)");

  private SvgRasterizer() {}

  /**
   * @throws IllegalArgumentException SVG로 읽지 못했을 때
   */
  public static byte[] toPng(byte[] svg) {
    String text = QUOTED_URL.matcher(new String(svg, StandardCharsets.UTF_8)).replaceAll("url($2)");
    SVGDocument doc =
        new SVGLoader()
            .load(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                URI.create("about:blank"),
                CONTEXT);
    if (doc == null) {
      throw new IllegalArgumentException("SVG를 읽지 못함");
    }
    FloatSize size = doc.size();
    float w = size.width > 0 ? size.width : WIDTH;
    float h = size.height > 0 ? size.height : HEIGHT;
    float scale = Math.max(WIDTH / w, HEIGHT / h);
    float drawW = w * scale;
    float drawH = h * scale;

    BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
      g.setColor(Color.WHITE);
      g.fillRect(0, 0, WIDTH, HEIGHT);
      g.clipRect(0, 0, WIDTH, HEIGHT);
      doc.render(null, g, new ViewBox((WIDTH - drawW) / 2, (HEIGHT - drawH) / 2, drawW, drawH));
    } finally {
      g.dispose();
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      ImageIO.write(image, "png", out);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }
}
