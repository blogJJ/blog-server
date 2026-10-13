package com.blog.board.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.GpsDirectory;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

  @ParameterizedTest(name = "orientation {0}")
  @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8})
  @DisplayName("회전·뒤집기 정보가 있는 휴대폰 JPEG는 먼저 바로 세워 다시 쓰고 EXIF·GPS를 지운다 (BRD-05)")
  void rotatedJpegIsTurnedUprightBeforeExifIsRemoved(int orientation) throws Exception {
    // 80x40 가로 그림. 왼쪽 위 4분의 1만 빨강, 나머지는 파랑
    BufferedImage raw = new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 40; y++) {
      for (int x = 0; x < 80; x++) {
        raw.setRGB(x, y, x < 40 && y < 20 ? 0xFF0000 : 0x0000FF);
      }
    }
    byte[] jpeg = TestImages.withExif(TestImages.jpeg(raw, 0.95f), orientation, "HOME-ADDRESS");
    Metadata before = ImageMetadataReader.readMetadata(new ByteArrayInputStream(jpeg));
    assertThat(before.getFirstDirectoryOfType(GpsDirectory.class).getGeoLocation()).isNotNull();

    byte[] cleaned = ImageSanitizer.sanitize(jpeg, ImageType.JPEG);

    assertThat(contains(cleaned, "HOME-ADDRESS")).isFalse();
    assertThat(contains(cleaned, "Exif")).isFalse();
    Metadata after = ImageMetadataReader.readMetadata(new ByteArrayInputStream(cleaned));
    assertThat(after.getFirstDirectoryOfType(GpsDirectory.class)).isNull();
    assertThat(after.getFirstDirectoryOfType(ExifIFD0Directory.class)).isNull();

    // 빨간 칸이 가야 할 모서리: 왼쪽 위(0)·오른쪽 위(1)·왼쪽 아래(2)·오른쪽 아래(3)
    int corner =
        switch (orientation) {
          case 2 -> 1; // 좌우 뒤집기
          case 3 -> 3; // 180도
          case 4 -> 2; // 위아래 뒤집기
          case 5 -> 0; // 대각선 뒤집기
          case 6 -> 1; // 시계 방향 90도
          case 7 -> 3;
          default -> 2; // 8: 반시계 방향 90도
        };
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(cleaned));
    boolean swap = orientation >= 5;
    assertThat(image.getWidth()).isEqualTo(swap ? 40 : 80);
    assertThat(image.getHeight()).isEqualTo(swap ? 80 : 40);
    int w = image.getWidth();
    int h = image.getHeight();
    int[][] centers = {
      {w / 4, h / 4}, {w * 3 / 4, h / 4}, {w / 4, h * 3 / 4}, {w * 3 / 4, h * 3 / 4}
    };
    for (int c = 0; c < 4; c++) {
      assertThat(isRed(image.getRGB(centers[c][0], centers[c][1])))
          .as("모서리 %d", c)
          .isEqualTo(c == corner);
    }
  }

  @Test
  @DisplayName("돌릴 필요가 없는 JPEG는 다시 압축하지 않고 EXIF 구간만 빼 원래 바이트를 그대로 남긴다")
  void uprightJpegIsStrippedLosslessly() {
    byte[] original = TestImages.jpeg(TestImages.photo(64, 48, 5), 0.9f);
    byte[] withExif = TestImages.withExif(original, 1, "SECRET-PLACE");

    byte[] cleaned = ImageSanitizer.sanitize(withExif, ImageType.JPEG);

    assertThat(cleaned).isEqualTo(original);
  }

  @Test
  @DisplayName("회전해야 하지만 풀 수 없는 JPEG(지원하지 않는 압축 방식)도 EXIF·GPS는 지운다")
  void undecodableRotatedJpegStillLosesExif() throws Exception {
    byte[] jpeg = TestImages.withExif(TestImages.jpeg(), 6, "SECRET-PLACE");
    // SOF0(기본 방식)을 SOF3(무손실 방식)으로 바꾸면 ImageIO가 풀지 못한다
    byte[] lossless = jpeg.clone();
    for (int i = 2; i < lossless.length - 1; i++) {
      if ((lossless[i] & 0xFF) == 0xFF && (lossless[i + 1] & 0xFF) == 0xC0) {
        lossless[i + 1] = (byte) 0xC3;
        break;
      }
    }
    byte[] unsupported = lossless;
    assertThatThrownBy(() -> ImageIO.read(new ByteArrayInputStream(unsupported)))
        .isInstanceOf(javax.imageio.IIOException.class);

    byte[] cleaned = ImageSanitizer.sanitize(unsupported, ImageType.JPEG);

    assertThat(contains(cleaned, "SECRET-PLACE")).isFalse();
    assertThat(contains(cleaned, "Exif")).isFalse();
    assertThat(cleaned.length).isEqualTo(TestImages.jpeg().length);
  }

  @Test
  @DisplayName("JPEG의 IPTC(APP13) 구간도 지운다. 구간 앞 채움 바이트(FF)가 있어도 읽는다")
  void jpegLosesIptcSegmentAfterFillBytes() throws Exception {
    byte[] jpeg = TestImages.jpeg();
    byte[] payload = "Photoshop 3.0\08BIM IPTC-CITY=Seoul".getBytes(StandardCharsets.ISO_8859_1);
    byte[] app13 = new byte[4 + payload.length];
    app13[0] = (byte) 0xFF;
    app13[1] = (byte) 0xED;
    app13[2] = (byte) ((payload.length + 2) >> 8);
    app13[3] = (byte) (payload.length + 2);
    System.arraycopy(payload, 0, app13, 4, payload.length);
    byte[] withIptc =
        concat(
            Arrays.copyOf(jpeg, 2),
            new byte[] {(byte) 0xFF, (byte) 0xFF},
            app13,
            Arrays.copyOfRange(jpeg, 2, jpeg.length));

    byte[] cleaned = ImageSanitizer.sanitize(withIptc, ImageType.JPEG);

    assertThat(contains(cleaned, "IPTC-CITY")).isFalse();
    assertThat(contains(cleaned, "Photoshop")).isFalse();
    assertThat(ImageIO.read(new ByteArrayInputStream(cleaned)).getWidth()).isEqualTo(4);
  }

  @Test
  @DisplayName("구간 구조가 깨진 JPEG는 지우지 못하므로 받지 않는다 (Broken JPEG)")
  void brokenJpegIsRejected() {
    byte[] jpeg = TestImages.jpeg();
    // 머리 구간 중간에서 잘린 파일 (영상 데이터 시작이 없다)
    assertThatThrownBy(() -> ImageSanitizer.sanitize(Arrays.copyOf(jpeg, 12), ImageType.JPEG))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Broken JPEG");
    // SOI 뒤에 표지(FF)가 아닌 바이트
    byte[] garbage = concat(Arrays.copyOf(jpeg, 2), "not a segment".getBytes());
    assertThatThrownBy(() -> ImageSanitizer.sanitize(garbage, ImageType.JPEG))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Broken JPEG");
    // 길이 칸이 2보다 작은 구간
    byte[] badLength = jpeg.clone();
    badLength[4] = 0;
    badLength[5] = 1;
    assertThatThrownBy(() -> ImageSanitizer.sanitize(badLength, ImageType.JPEG))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Broken JPEG");
  }

  @Test
  @DisplayName("PNG의 eXIf·iTXt·zTXt·tIME 조각을 모두 지우고 그림 화소는 그대로 둔다")
  void pngLosesExifAndAllTextAndTimeChunks() throws Exception {
    byte[] png = TestImages.encode(TestImages.photo(20, 10, 9), "png");
    byte[] dirty = TestImages.pngWithChunk(png, "eXIf", "MM\0*GPS-37.56".getBytes());
    dirty = TestImages.pngWithChunk(dirty, "iTXt", "Author\0\0\0\0\0Kim".getBytes());
    dirty = TestImages.pngWithChunk(dirty, "zTXt", "Comment\0\0xx".getBytes());
    dirty = TestImages.pngWithChunk(dirty, "tIME", new byte[] {0x07, (byte) 0xE9, 1, 2, 3, 4, 5});
    assertThat(contains(dirty, "GPS-37.56")).isTrue();

    byte[] cleaned = ImageSanitizer.sanitize(dirty, ImageType.PNG);

    assertThat(cleaned).isEqualTo(png);
  }

  @Test
  @DisplayName("IEND가 없거나 조각 길이가 파일보다 긴 PNG는 받지 않는다 (Broken PNG)")
  void brokenPngIsRejected() {
    byte[] png = TestImages.png();
    assertThatThrownBy(
            () -> ImageSanitizer.sanitize(Arrays.copyOf(png, png.length - 12), ImageType.PNG))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Broken PNG");
    byte[] tooLong = png.clone();
    ByteBuffer.wrap(tooLong, 8, 4).putInt(1_000_000); // IHDR 길이
    assertThatThrownBy(() -> ImageSanitizer.sanitize(tooLong, ImageType.PNG))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Broken PNG");
  }

  @Test
  @DisplayName("진짜 WebP에서 EXIF·XMP 조각을 빼고 VP8X 표시를 끄면 그대로 풀리는 WebP가 남는다")
  void realWebpStaysDecodableAfterExifAndXmpRemoved() {
    byte[] simple = TestImages.encode(TestImages.photo(64, 48, 4), "webp");
    assertThat(new String(simple, 12, 4, StandardCharsets.ISO_8859_1)).isEqualTo("VP8 ");
    byte[] vp8 = Arrays.copyOfRange(simple, 12, simple.length);
    byte[] vp8x = new byte[10];
    vp8x[0] = 0x08 | 0x04; // EXIF, XMP 표시
    vp8x[4] = 63; // 가로 - 1
    vp8x[7] = 47; // 세로 - 1
    byte[] webp =
        TestImages.webpFile(
            TestImages.webpChunk("VP8X", vp8x),
            vp8,
            TestImages.webpChunk("EXIF", "MM\0*GPS-SECRET".getBytes()),
            TestImages.webpChunk("XMP ", "<x:xmpmeta>LENS-123</x:xmpmeta>".getBytes()));

    byte[] cleaned = ImageSanitizer.sanitize(webp, ImageType.WEBP);

    assertThat(contains(cleaned, "GPS-SECRET")).isFalse();
    assertThat(contains(cleaned, "LENS-123")).isFalse();
    assertThat(cleaned[20] & 0x0C).isZero();
    assertThat(ByteBuffer.wrap(cleaned, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt())
        .isEqualTo(cleaned.length - 8);
    BufferedImage image = TestImages.readWebp(cleaned);
    assertThat(image.getWidth()).isEqualTo(64);
    assertThat(image.getHeight()).isEqualTo(48);
  }

  @Test
  @DisplayName("마지막 조각의 채움 바이트가 빠진 WebP는 받고, 조각 길이가 파일을 넘으면 받지 않는다")
  void webpWithMissingLastPadIsAcceptedButOverrunIsRejected() {
    byte[] exif = TestImages.webpChunk("EXIF", "SECRET".getBytes());
    byte[] vp8l = TestImages.webpChunk("VP8L", new byte[] {0x2F, 0, 0, 0, 0}); // 길이 5 + 채움 1
    byte[] noPad = TestImages.webpFile(exif, Arrays.copyOf(vp8l, vp8l.length - 1));

    byte[] cleaned = ImageSanitizer.sanitize(noPad, ImageType.WEBP);

    assertThat(contains(cleaned, "SECRET")).isFalse();
    assertThat(new String(cleaned, 12, 4, StandardCharsets.ISO_8859_1)).isEqualTo("VP8L");
    assertThat(cleaned.length).isEqualTo(12 + 8 + 5);

    byte[] overrun = TestImages.webpFile(exif, vp8l);
    ByteBuffer.wrap(overrun, 12 + exif.length + 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(500);
    assertThatThrownBy(() -> ImageSanitizer.sanitize(overrun, ImageType.WEBP))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Broken WebP");
  }

  private static boolean isRed(int rgb) {
    int r = rgb >> 16 & 0xFF;
    int b = rgb & 0xFF;
    return r > 180 && b < 80;
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
