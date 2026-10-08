package com.blog.board.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** 메타데이터 지우기 (T062, BRD-05). 지운 뒤에도 그림으로 읽혀야 한다. */
class ImageSanitizerTest {

  @Test
  void jpegLosesExifButStaysReadable() throws Exception {
    byte[] jpeg = TestImages.jpegWithExif("GPS-SECRET");
    assertThat(contains(jpeg, "GPS-SECRET")).isTrue();

    byte[] cleaned = ImageSanitizer.sanitize(jpeg, ImageType.JPEG);

    assertThat(contains(cleaned, "Exif")).isFalse();
    assertThat(contains(cleaned, "GPS-SECRET")).isFalse();
    assertThat(ImageIO.read(new ByteArrayInputStream(cleaned))).isNotNull();
  }

  @Test
  void pngLosesTextChunks() throws Exception {
    byte[] png = TestImages.pngWithText("location=Seoul");
    assertThat(contains(png, "location=Seoul")).isTrue();

    byte[] cleaned = ImageSanitizer.sanitize(png, ImageType.PNG);

    assertThat(contains(cleaned, "location=Seoul")).isFalse();
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(cleaned));
    assertThat(image.getWidth()).isEqualTo(4);
  }

  @Test
  void webpLosesExifChunkAndFixesSize() {
    byte[] webp = TestImages.webpWithExif("CAMERA-123");

    byte[] cleaned = ImageSanitizer.sanitize(webp, ImageType.WEBP);

    assertThat(contains(cleaned, "CAMERA-123")).isFalse();
    int riffSize =
        (cleaned[4] & 0xFF)
            | (cleaned[5] & 0xFF) << 8
            | (cleaned[6] & 0xFF) << 16
            | (cleaned[7] & 0xFF) << 24;
    assertThat(riffSize).isEqualTo(cleaned.length - 8);
    assertThat(cleaned[20] & 0x08).isZero(); // VP8X의 EXIF 표시
  }

  @Test
  void signatureDetection() throws Exception {
    assertThat(ImageType.bySignature(TestImages.png())).contains(ImageType.PNG);
    assertThat(ImageType.bySignature("<svg xmlns=".getBytes(StandardCharsets.UTF_8))).isEmpty();
    ByteArrayOutputStream gif = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "gif", gif);
    assertThat(ImageType.bySignature(gif.toByteArray())).contains(ImageType.GIF);
  }

  static boolean contains(byte[] haystack, String needle) {
    byte[] n = needle.getBytes(StandardCharsets.ISO_8859_1);
    outer:
    for (int i = 0; i <= haystack.length - n.length; i++) {
      for (int j = 0; j < n.length; j++) {
        if (haystack[i + j] != n[j]) {
          continue outer;
        }
      }
      return true;
    }
    return false;
  }

  static byte[] concat(byte[]... parts) {
    int total = Arrays.stream(parts).mapToInt(p -> p.length).sum();
    byte[] out = new byte[total];
    int pos = 0;
    for (byte[] p : parts) {
      System.arraycopy(p, 0, out, pos, p.length);
      pos += p.length;
    }
    return out;
  }
}
