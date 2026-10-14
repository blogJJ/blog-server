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
import java.util.Arrays;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.junit.jupiter.api.DisplayName;
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
    assertThat(result.data()).hasSizeLessThan(jpeg.length / 3);
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
    assertThat(result.data()).hasSizeLessThan(png.length);
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

  @Test
  @DisplayName("줄이지도 돌리지도 않았는데 WebP가 별로 작지 않으면 원래 JPEG(EXIF만 뺀 것)를 그대로 둔다")
  void wellCompressedJpegIsKeptInsteadOfRecompressed() {
    byte[] original = TestImages.jpeg(TestImages.photo(800, 600, 11), 0.3f);
    byte[] jpeg = TestImages.withExif(original, 1, "SECRET-PLACE");

    Result result = ImageOptimizer.optimize(jpeg, ImageType.JPEG, Limit.POST);

    assertThat(result.type()).isEqualTo(ImageType.JPEG);
    assertThat(result.data()).isEqualTo(original);
  }

  @Test
  @DisplayName("사진 같은 PNG(화소당 1바이트 초과)는 무손실 대신 손실 WebP로 쓴다")
  void photoLikePngBecomesLossyWebp() {
    byte[] png = TestImages.encode(TestImages.photo(800, 600, 12), "png");
    assertThat((double) png.length / (800 * 600)).isGreaterThan(1.0);

    Result result = ImageOptimizer.optimize(png, ImageType.PNG, Limit.POST);

    assertThat(result.type()).isEqualTo(ImageType.WEBP);
    assertThat(new String(result.data(), 12, 4, StandardCharsets.ISO_8859_1)).isEqualTo("VP8 ");
    assertThat(result.data()).hasSizeLessThan(png.length / 4);
    assertThat(ImageOptimizer.webpDimensions(result.data())).containsExactly(800, 600);
  }

  @Test
  @DisplayName("WebP 한도(긴 변 16383px)를 넘는 긴 캡처는 원래 형식(PNG)으로 남는다")
  void tallScreenshotBeyondWebpLimitStaysPng() throws Exception {
    BufferedImage tall = new BufferedImage(300, 17_000, BufferedImage.TYPE_INT_RGB);
    paint(tall, 0, 0, 300, 17_000, 0xFFFFFF);
    paint(tall, 0, 16_000, 300, 1_000, 0x0000FF);
    byte[] png = TestImages.encode(tall, "png");

    Result result = ImageOptimizer.optimize(png, ImageType.PNG, Limit.POST);

    assertThat(result.type()).isEqualTo(ImageType.PNG);
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(result.data()));
    assertThat(image.getWidth()).isEqualTo(300);
    assertThat(image.getHeight()).isEqualTo(17_000);
    assertThat(image.getRGB(150, 16_500) & 0xFFFFFF).isEqualTo(0x0000FF);
  }

  @Test
  @DisplayName("WebP 한도를 넘는 JPEG도 회전 정보대로 세워 JPEG로 다시 쓰고 EXIF·GPS를 남기지 않는다")
  void tallRotatedJpegBeyondWebpLimitIsReencodedAsUprightJpeg() throws Exception {
    // 가로 17000 x 세로 200으로 저장됐고 회전 정보 6(시계 방향 90도)으로 세워 보는 사진. 왼쪽 끝이 빨강
    BufferedImage raw = new BufferedImage(17_000, 200, BufferedImage.TYPE_INT_RGB);
    paint(raw, 0, 0, 17_000, 200, 0x808080);
    paint(raw, 0, 0, 500, 200, 0xFF0000);
    byte[] jpeg = TestImages.withExif(TestImages.jpeg(raw, 0.9f), 6, "SECRET-PLACE");

    Result result = ImageOptimizer.optimize(jpeg, ImageType.JPEG, Limit.POST);

    assertThat(result.type()).isEqualTo(ImageType.JPEG);
    assertThat(contains(result.data(), "SECRET-PLACE")).isFalse();
    Metadata after = ImageMetadataReader.readMetadata(new ByteArrayInputStream(result.data()));
    assertThat(after.getFirstDirectoryOfType(GpsDirectory.class)).isNull();
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(result.data()));
    assertThat(image.getWidth()).isEqualTo(200);
    assertThat(image.getHeight()).isEqualTo(17_000);
    assertThat(isReddish(image.getRGB(100, 200))).isTrue(); // 왼쪽 끝이 위로 간다
    assertThat(isReddish(image.getRGB(100, 16_800))).isFalse();
  }

  @Test
  @DisplayName("2400만 화소가 넘는 사진은 솎아 읽어 줄여도 프로필 크기와 그림 배치가 맞다")
  void hugePhotoIsSubsampledWhileDecodingAndStillFitsProfile() {
    BufferedImage raw = new BufferedImage(6000, 4500, BufferedImage.TYPE_INT_RGB);
    paint(raw, 0, 0, 3000, 4500, 0xFF0000); // 왼쪽 절반 빨강
    paint(raw, 3000, 0, 3000, 4500, 0x0000FF); // 오른쪽 절반 파랑
    byte[] jpeg = TestImages.jpeg(raw, 0.8f);

    Result result = ImageOptimizer.optimize(jpeg, ImageType.JPEG, Limit.PROFILE);

    assertThat(result.type()).isEqualTo(ImageType.WEBP);
    BufferedImage image = TestImages.readWebp(result.data());
    assertThat(image.getWidth()).isEqualTo(512);
    assertThat(image.getHeight()).isEqualTo(384);
    assertThat(isReddish(image.getRGB(100, 192))).isTrue();
    assertThat(image.getRGB(400, 192) & 0xFF).isGreaterThan(200);
  }

  @Test
  @DisplayName("움직이는 PNG(APNG)는 풀지 않고 메타데이터만 지워 그대로 둔다")
  void animatedPngIsKeptAndOnlyCleaned() {
    byte[] png = TestImages.encode(TestImages.photo(400, 300, 13), "png");
    byte[] actl = {0, 0, 0, 2, 0, 0, 0, 0}; // 장면 2개, 무한 반복
    byte[] apng = TestImages.pngWithChunk(png, "acTL", actl);
    byte[] dirty = TestImages.pngWithChunk(apng, "tEXt", "GPS\037.5,127.0".getBytes());

    Result result = ImageOptimizer.optimize(dirty, ImageType.PNG, Limit.POST);

    assertThat(ImageOptimizer.isAnimatedPng(dirty, ImageType.PNG)).isTrue();
    assertThat(ImageOptimizer.isAnimatedPng(png, ImageType.PNG)).isFalse();
    assertThat(result.type()).isEqualTo(ImageType.PNG);
    assertThat(result.data()).isEqualTo(apng);
  }

  @Test
  @DisplayName("머리가 깨졌거나 가로·세로가 0인 파일은 '손상'(IllegalArgumentException)으로 막는다")
  void brokenHeadersAreRejectedAsBroken() {
    byte[] gif = TestImages.animatedGif();
    byte[] cutGif = Arrays.copyOf(gif, 8);
    assertThatThrownBy(() -> ImageOptimizer.optimize(cutGif, ImageType.GIF, Limit.POST))
        .isInstanceOf(IllegalArgumentException.class);
    byte[] zeroWidth = gif.clone();
    zeroWidth[6] = zeroWidth[7] = 0;
    assertThatThrownBy(() -> ImageOptimizer.optimize(zeroWidth, ImageType.GIF, Limit.POST))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Broken image");
    byte[] jpeg = TestImages.jpeg();
    byte[] cutJpeg = Arrays.copyOf(jpeg, 20);
    assertThatThrownBy(() -> ImageOptimizer.optimize(cutJpeg, ImageType.JPEG, Limit.POST))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Broken image");
  }

  @Test
  @DisplayName("WebP 머리는 VP8·VP8L 시작 표시가 틀리거나 너무 짧거나 모르는 조각이면 '손상'이다")
  void brokenWebpHeadersAreRejected() {
    byte[] lossy =
        ImageOptimizer.optimize(
                TestImages.jpeg(TestImages.photo(320, 240, 14), 0.95f), ImageType.JPEG, Limit.POST)
            .data();
    assertThat(new String(lossy, 12, 4, StandardCharsets.ISO_8859_1)).isEqualTo("VP8 ");
    byte[] badStartCode = lossy.clone();
    badStartCode[23] = 0;
    byte[] lossless =
        ImageOptimizer.optimize(TestImages.pngWithAlpha(300, 200), ImageType.PNG, Limit.POST)
            .data();
    assertThat(new String(lossless, 12, 4, StandardCharsets.ISO_8859_1)).isEqualTo("VP8L");
    byte[] badSignature = lossless.clone();
    badSignature[20] = 0;
    byte[] unknown = lossy.clone();
    System.arraycopy("JUNK".getBytes(StandardCharsets.ISO_8859_1), 0, unknown, 12, 4);

    for (byte[] broken :
        new byte[][] {badStartCode, badSignature, unknown, Arrays.copyOf(lossy, 29)}) {
      assertThatThrownBy(() -> ImageOptimizer.optimize(broken, ImageType.WEBP, Limit.POST))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Broken WebP");
    }
  }

  @Test
  @DisplayName("요청 스레드가 끊기면 줄이지 않고 메타데이터만 지운 원래 파일을 돌려주며 끊김 표시는 남긴다")
  void interruptedRequestKeepsCleanedOriginal() {
    byte[] original = TestImages.jpeg(TestImages.photo(2400, 1800, 15), 0.9f);
    byte[] jpeg = TestImages.withExif(original, 1, "SECRET-PLACE");

    Thread.currentThread().interrupt();
    Result result;
    try {
      result = ImageOptimizer.optimize(jpeg, ImageType.JPEG, Limit.POST);
    } finally {
      assertThat(Thread.interrupted()).isTrue();
    }

    assertThat(result.type()).isEqualTo(ImageType.JPEG);
    assertThat(result.data()).isEqualTo(original);
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

  @Test
  @DisplayName("PNG 조각 길이가 int 끝에 가까운 값이어도 움직이는 PNG 검사가 넘치지 않고 끝난다 (500 대신 손상으로 막히게)")
  void hugeChunkLengthDoesNotOverflowAnimatedPngCheck() {
    // IHDR 다음 조각의 길이를 0x7FFFFFF0으로 꾸민다. int로 더하면 다음 위치가 음수가 되어 예외가 났다
    byte[] png = TestImages.pngWithChunk(TestImages.png(), "tEXt", new byte[4]);
    int at = 8 + 12 + 13;
    png[at] = 0x7F;
    png[at + 1] = (byte) 0xFF;
    png[at + 2] = (byte) 0xFF;
    png[at + 3] = (byte) 0xF0;

    assertThat(ImageOptimizer.isAnimatedPng(png, ImageType.PNG)).isFalse();
  }
}
