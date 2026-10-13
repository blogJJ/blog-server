package com.blog.board.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class SvgRasterizerTest {

  @Test
  void drawsSanitizedCoverAs1200x630Png() throws Exception {
    String svg;
    try (InputStream in = getClass().getResourceAsStream("/cover-art/cafe-raw.svg")) {
      svg = SvgSanitizer.sanitize(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
    byte[] png = SvgRasterizer.toPng(svg.getBytes(StandardCharsets.UTF_8));

    BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
    assertThat(image.getWidth()).isEqualTo(1200);
    assertThat(image.getHeight()).isEqualTo(630);
    // 1200x500 그림을 높이에 맞춰 키워 가운데를 잘랐으니 위아래 끝까지 그림이 있다(흰 띠가 없다)
    assertThat(distinctColors(image)).isGreaterThan(5);
    assertThat(image.getRGB(600, 2) & 0xFFFFFF).isNotEqualTo(0xFFFFFF);
    assertThat(image.getRGB(600, 627) & 0xFFFFFF).isNotEqualTo(0xFFFFFF);
  }

  @Test
  void wideSvgFillsWholeCanvas() throws Exception {
    String svg =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 10\">"
            + "<rect width=\"100\" height=\"10\" fill=\"#ff0000\"/></svg>";
    BufferedImage image =
        ImageIO.read(
            new ByteArrayInputStream(SvgRasterizer.toPng(svg.getBytes(StandardCharsets.UTF_8))));
    assertThat(image.getRGB(0, 0) & 0xFFFFFF).isEqualTo(0xFF0000);
    assertThat(image.getRGB(1199, 629) & 0xFFFFFF).isEqualTo(0xFF0000);
  }

  /** 정리기는 {@code url('#sun')}을 그대로 두는데, 그대로 넘기면 검게 칠해진다. */
  @Test
  void quotedGradientReferenceIsNotBlack() throws Exception {
    String svg =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 120 63\"><defs>"
            + "<linearGradient id=\"g\"><stop offset=\"0\" stop-color=\"#00ff00\"/>"
            + "<stop offset=\"1\" stop-color=\"#00ff00\"/></linearGradient></defs>"
            + "<rect width=\"120\" height=\"63\" fill=\"url('#g')\"/></svg>";
    BufferedImage image =
        ImageIO.read(
            new ByteArrayInputStream(SvgRasterizer.toPng(svg.getBytes(StandardCharsets.UTF_8))));
    assertThat(image.getRGB(600, 315) & 0xFFFFFF).isEqualTo(0x00FF00);
  }

  @Test
  void rejectsNonSvg() {
    assertThatThrownBy(() -> SvgRasterizer.toPng("not svg".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(RuntimeException.class);
  }

  private static int distinctColors(BufferedImage image) {
    java.util.Set<Integer> colors = new java.util.HashSet<>();
    for (int y = 0; y < image.getHeight(); y += 10) {
      for (int x = 0; x < image.getWidth(); x += 10) {
        colors.add(image.getRGB(x, y));
      }
    }
    return colors.size();
  }
}
