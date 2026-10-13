package com.blog.board.service;

import static com.blog.board.service.ImageSanitizerTest.contains;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blog.board.service.ImageOptimizer.Limit;
import com.blog.board.service.ImageOptimizer.Result;
import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.GpsDirectory;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.junit.jupiter.api.Test;

/** 올린 이미지 줄이기 (T162). */
class ImageOptimizerTest {

  @Test
  void webpEncoderWorksHere() {
    assertThat(ImageOptimizer.webpAvailable()).isTrue();
  }

  @Test
  void largePhoneJpegIsUprightDownscaledAndLosesExifAndGps() throws Exception {
    // 가로로 찍혀 저장됐지만 회전 정보(6)로 세워 보여 주는 휴대폰 사진. 위쪽 왼편에 빨간 띠를 둔다
    BufferedImage raw = TestImages.photo(4000, 3000, 1);
    paint(raw, 0, 0, 4000, 300, 0xFF0000);
    byte[] jpeg = TestImages.withExif(TestImages.jpeg(raw, 0.92f), 6, "SECRET-PLACE");
    Metadata before = ImageMetadataReader.readMetadata(new ByteArrayInputStream(jpeg));
    assertThat(before.getFirstDirectoryOfType(GpsDirectory.class).getGeoLocation()).isNotNull();
    assertThat(before.getFirstDirectoryOfType(ExifIFD0Directory.class).getInt(0x0112)).isEqualTo(6);

    Result result = ImageOptimizer.optimize(jpeg, ImageType.JPEG, Limit.POST);

    assertThat(result.type()).isEqualTo(ImageType.WEBP);
    assertThat(ImageType.bySignature(result.data())).contains(ImageType.WEBP);
    assertThat(result.data().length).isLessThan(jpeg.length / 3);
    assertThat(contains(result.data(), "SECRET-PLACE")).isFalse();
    assertThat(contains(result.data(), "Exif")).isFalse();
    Metadata after = ImageMetadataReader.readMetadata(new ByteArrayInputStream(result.data()));
    assertThat(after.getFirstDirectoryOfType(GpsDirectory.class)).isNull();
    assertThat(after.getFirstDirectoryOfType(ExifIFD0Directory.class)).isNull();

    // 시계 방향으로 세워 1600 x 2133. 빨간 띠는 오른쪽 끝 세로 띠가 된다
    BufferedImage image = TestImages.readWebp(result.data());
    assertThat(image.getWidth()).isEqualTo(1600);
    assertThat(image.getHeight()).isEqualTo(2133);
    assertThat(isReddish(image.getRGB(1590, 1000))).isTrue();
    assertThat(isReddish(image.getRGB(10, 1000))).isFalse();
  }

  @Test
  void everyExifOrientationIsApplied() {
    // 2x1 칸: 왼쪽 빨강, 오른쪽 파랑. 각 회전값에서 빨강이 가는 자리
    int[][] redAt = {{}, {0, 0}, {1, 0}, {1, 1}, {0, 1}, {0, 0}, {1, 0}, {1, 1}, {0, 1}};
    for (int orientation = 1; orientation <= 8; orientation++) {
      BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
      image.setRGB(0, 0, 0xFF0000);
      image.setRGB(1, 0, 0x0000FF);
      BufferedImage turned = ImageOptimizer.orient(image, orientation);
      assertThat(turned.getRGB(redAt[orientation][0], redAt[orientation][1]) & 0xFFFFFF)
          .as("orientation %d", orientation)
          .isEqualTo(0xFF0000);
    }
  }

  @Test
  void pngKeepsTransparencyWhenDownscaled() {
    byte[] png = TestImages.pngWithAlpha(2400, 1200);

    Result result = ImageOptimizer.optimize(png, ImageType.PNG, Limit.POST);

    assertThat(result.type()).isEqualTo(ImageType.WEBP);
    BufferedImage image = TestImages.readWebp(result.data());
    assertThat(image.getWidth()).isEqualTo(1600);
    assertThat(image.getHeight()).isEqualTo(800);
    assertThat(image.getColorModel().hasAlpha()).isTrue();
    assertThat(image.getRGB(1500, 400) >>> 24).isZero(); // 투명한 쪽
    int alpha = image.getRGB(200, 700) >>> 24; // 반투명한 쪽 (원래 231)
    assertThat(alpha).isBetween(220, 242);
  }

  @Test
  void smallPngWithAlphaBecomesLosslessWebp() {
    byte[] png = TestImages.pngWithAlpha(300, 200);

    Result result = ImageOptimizer.optimize(png, ImageType.PNG, Limit.POST);

    assertThat(result.type()).isEqualTo(ImageType.WEBP);
    assertThat(result.data().length).isLessThan(png.length);
    BufferedImage image = TestImages.readWebp(result.data());
    assertThat(image.getWidth()).isEqualTo(300);
    // 무손실이라 화소 값이 그대로다
    BufferedImage original = readPng(png);
    assertThat(image.getRGB(50, 150)).isEqualTo(original.getRGB(50, 150));
    assertThat(image.getRGB(250, 10) >>> 24).isZero();
  }

  @Test
  void animatedGifIsKeptAsIs() throws Exception {
    byte[] gif = TestImages.animatedGif();

    Result result = ImageOptimizer.optimize(gif, ImageType.GIF, Limit.POST);

    assertThat(result.type()).isEqualTo(ImageType.GIF);
    assertThat(result.data()).isEqualTo(gif);
    ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
    try (ImageInputStream in =
        ImageIO.createImageInputStream(new ByteArrayInputStream(result.data()))) {
      reader.setInput(in);
      assertThat(reader.getNumImages(true)).isEqualTo(3);
    } finally {
      reader.dispose();
    }
  }

  @Test
  void tooManyPixelsAreRejectedBeforeDecoding() {
    // 그림 데이터는 엉터리라 풀려고 했다면 '깨진 파일'이 됐을 것이다
    assertThatThrownBy(
            () ->
                ImageOptimizer.optimize(
                    TestImages.pngHeader(10_000, 10_000), ImageType.PNG, Limit.POST))
        .isInstanceOf(ImageOptimizer.TooManyPixelsException.class);
    byte[] gif = TestImages.animatedGif();
    gif[6] = gif[7] = gif[8] = gif[9] = (byte) 0xFF; // 논리 화면 65535 x 65535
    assertThatThrownBy(() -> ImageOptimizer.optimize(gif, ImageType.GIF, Limit.POST))
        .isInstanceOf(ImageOptimizer.TooManyPixelsException.class);
    assertThatThrownBy(
            () -> ImageOptimizer.optimize(webpCanvas(16000, 16000), ImageType.WEBP, Limit.POST))
        .isInstanceOf(ImageOptimizer.TooManyPixelsException.class);
    // 한도 안쪽이면 머리 검사는 통과한다. 풀지 못하는 그림은 예전처럼 메타데이터만 지워 둔다
    byte[] small = TestImages.pngHeader(100, 100);
    assertThat(ImageOptimizer.optimize(small, ImageType.PNG, Limit.POST).type())
        .isEqualTo(ImageType.PNG);
  }

  @Test
  void profileImageFitsInSquare() {
    byte[] jpeg = TestImages.jpeg(TestImages.photo(1200, 900, 2), 0.9f);

    Result result = ImageOptimizer.optimize(jpeg, ImageType.JPEG, Limit.PROFILE);

    BufferedImage image = TestImages.readWebp(result.data());
    assertThat(image.getWidth()).isEqualTo(512);
    assertThat(image.getHeight()).isEqualTo(384);
  }

  @Test
  void uploadedWebpIsOnlyCleanedNotDecoded() {
    byte[] webp = TestImages.webpWithExif("CAMERA-123");

    Result result = ImageOptimizer.optimize(webp, ImageType.WEBP, Limit.POST);

    assertThat(result.type()).isEqualTo(ImageType.WEBP);
    assertThat(contains(result.data(), "CAMERA-123")).isFalse();
    // 서버는 WebP 읽기 플러그인을 빼 둔다 (남이 올린 WebP를 libwebp로 풀지 않는다)
    assertThat(ImageIO.getImageReadersByFormatName("webp").hasNext()).isFalse();
  }

  @Test
  void readsWebpHeaderSizes() {
    byte[] lossy =
        ImageOptimizer.optimize(
                TestImages.jpeg(TestImages.photo(640, 480, 3), 0.95f), ImageType.JPEG, Limit.POST)
            .data();
    assertThat(ImageOptimizer.webpDimensions(lossy)).containsExactly(640, 480);
    byte[] lossless =
        ImageOptimizer.optimize(TestImages.pngWithAlpha(300, 200), ImageType.PNG, Limit.POST)
            .data();
    assertThat(ImageOptimizer.webpDimensions(lossless)).containsExactly(300, 200);
    assertThat(ImageOptimizer.webpDimensions(webpCanvas(16000, 9000))).containsExactly(16000, 9000);
  }

  private static byte[] webpCanvas(int width, int height) {
    ByteBuffer b = ByteBuffer.allocate(30 + 8).order(ByteOrder.LITTLE_ENDIAN);
    b.put("RIFF".getBytes(StandardCharsets.ISO_8859_1)).putInt(30);
    b.put("WEBP".getBytes(StandardCharsets.ISO_8859_1));
    b.put("VP8X".getBytes(StandardCharsets.ISO_8859_1)).putInt(10);
    b.putInt(0);
    b.put((byte) (width - 1)).put((byte) ((width - 1) >> 8)).put((byte) ((width - 1) >> 16));
    b.put((byte) (height - 1)).put((byte) ((height - 1) >> 8)).put((byte) ((height - 1) >> 16));
    b.put("ANMF".getBytes(StandardCharsets.ISO_8859_1)).putInt(0);
    return b.array();
  }

  private static void paint(BufferedImage image, int x, int y, int w, int h, int rgb) {
    for (int j = y; j < y + h; j++) {
      for (int i = x; i < x + w; i++) {
        image.setRGB(i, j, rgb);
      }
    }
  }

  private static boolean isReddish(int rgb) {
    int r = rgb >> 16 & 0xFF;
    int g = rgb >> 8 & 0xFF;
    int b = rgb & 0xFF;
    return r > 200 && g < 60 && b < 60;
  }

  private static BufferedImage readPng(byte[] png) {
    try {
      return ImageIO.read(new ByteArrayInputStream(png));
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
