package com.blog.board.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.luciad.imageio.webp.CompressionType;
import com.luciad.imageio.webp.WebPImageReaderSpi;
import com.luciad.imageio.webp.WebPImageWriterSpi;
import com.luciad.imageio.webp.WebPWriteParam;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 올린 이미지를 빨리 뜨게 줄인다 (T162). 메타데이터 지우기({@link ImageSanitizer})와 함께 저장 전에 한 번 거친다.
 *
 * <ul>
 *   <li>화소 수가 {@link #MAX_PIXELS}를 넘으면 그림을 풀기 전에 막는다. 파일 머리의 가로·세로만 읽는다 (압축 폭탄 방지).
 *   <li>JPEG·PNG는 그림을 풀어 EXIF 회전대로 세우고, 정해진 크기({@link Limit})보다 크면 줄여 WebP로 다시 쓴다. 다시 쓰면 EXIF·GPS
 *       같은 메타데이터는 하나도 남지 않는다. JPEG의 색 프로필(ICC, 아이폰의 Display P3 등)은 ImageIO가 풀 때 sRGB로 바꿔 준다.
 *   <li>JPEG는 손실 WebP(품질 {@link #PHOTO_QUALITY}), PNG는 무손실 WebP로 쓴다. 투명도는 그대로 남는다. 사진 같은 PNG(화소당
 *       1바이트 넘게 압축된 것)는 손실 WebP(투명도 유지)로 쓴다.
 *   <li>줄이지도 돌리지도 않았는데 새 파일이 별로 작아지지 않으면 원래 파일(메타데이터만 지운 것)을 그대로 둔다. 이미 잘 압축된 사진을 한 번 더 손실 압축하지
 *       않으려는 것이다.
 *   <li>GIF·WebP는 그대로 둔다(메타데이터만 지운다). 움직이는 그림이 멈추지 않게 하고, 서버에서 남이 올린 WebP를 네이티브 코드로 풀지 않으려는 것이다.
 *       움직이는 PNG(APNG)도 그대로 둔다.
 *   <li>WebP 인코더(libwebp, JNI)를 이 서버에서 못 쓰면 같은 규칙으로 줄여 JPEG·PNG로 쓴다. 동시에 그림을 푸는 요청은 {@link
 *       #CONCURRENCY}개까지만 받고, 자리가 나지 않으면 줄이지 않고 원래 파일로 저장한다(메모리 보호).
 * </ul>
 */
public final class ImageOptimizer {

  private static final Logger log = LoggerFactory.getLogger(ImageOptimizer.class);

  /** 받을 수 있는 화소 수 (가로×세로). 4800만 화소 휴대폰 사진까지 받는다 */
  public static final long MAX_PIXELS = 50_000_000L;

  static final float PHOTO_QUALITY = 0.80f;
  static final float GRAPHIC_LOSSY_QUALITY = 0.85f;

  /** 원래 PNG나 무손실 WebP가 화소당 이만큼 넘으면 사진 같은 그림으로 보고 손실 압축한다 */
  static final double LOSSLESS_MAX_BYTES_PER_PIXEL = 1.0;

  /** 줄이지도 돌리지도 않았을 때, 새 파일이 원래 파일의 이 비율보다 작아야 바꾼다 */
  static final double MIN_SAVING_RATIO = 0.9;

  /** 이 화소 수보다 큰 그림은 풀 때부터 솎아 읽어 메모리를 아낀다 */
  private static final long SUBSAMPLE_ABOVE_PIXELS = 24_000_000L;

  /** WebP가 담을 수 있는 가장 긴 변 */
  private static final int WEBP_MAX_DIMENSION = 16383;

  static final int CONCURRENCY = 2;
  private static final Semaphore SLOTS = new Semaphore(CONCURRENCY);
  private static final long SLOT_WAIT_SECONDS = 20;

  private static final boolean WEBP_AVAILABLE = initWebp();

  /** 이미지 쓰임별 크기 상한 (화면에서 보이는 크기의 2배 정도). */
  public enum Limit {
    /** 글 본문 이미지: 가로 1600px */
    POST(1600, Integer.MAX_VALUE),
    /** 블로그 대표 이미지: 가로 1600px */
    COVER(1600, Integer.MAX_VALUE),
    /** 프로필 사진: 긴 변 512px */
    PROFILE(512, 512);

    final int maxWidth;
    final int maxHeight;

    Limit(int maxWidth, int maxHeight) {
      this.maxWidth = maxWidth;
      this.maxHeight = maxHeight;
    }
  }

  /** 저장할 결과. type은 실제로 쓴 형식이다 (JPEG를 올려도 WebP가 될 수 있다). */
  public record Result(ImageType type, byte[] data) {}

  /** 화소 수가 너무 많아 받지 않는 그림. */
  public static final class TooManyPixelsException extends RuntimeException {
    TooManyPixelsException(long pixels) {
      super("Too many pixels: " + pixels);
    }
  }

  private ImageOptimizer() {}

  /** WebP로 저장하는지. 인코더를 못 쓰는 환경이면 false */
  public static boolean webpAvailable() {
    return WEBP_AVAILABLE;
  }

  /**
   * 줄이고 메타데이터를 지운 결과를 돌려준다.
   *
   * @param data 올린 파일 그대로 (종류·시그니처 검사는 끝난 것)
   * @throws TooManyPixelsException 화소가 너무 많을 때
   * @throws IllegalArgumentException 파일이 깨졌을 때
   */
  public static Result optimize(byte[] data, ImageType type, Limit limit) {
    long[] size = dimensions(data, type);
    long pixels = size[0] * size[1];
    if (size[0] <= 0 || size[1] <= 0) {
      throw new IllegalArgumentException("Broken image");
    }
    if (pixels > MAX_PIXELS) {
      throw new TooManyPixelsException(pixels);
    }
    if (type == ImageType.GIF || type == ImageType.WEBP || isAnimatedPng(data, type)) {
      return new Result(type, ImageSanitizer.sanitize(data, type));
    }
    boolean acquired = false;
    Result result = null;
    try {
      acquired = SLOTS.tryAcquire(SLOT_WAIT_SECONDS, TimeUnit.SECONDS);
      if (acquired) {
        result = reencode(data, type, (int) size[0], (int) size[1], limit);
      } else {
        log.warn("Image optimization skipped: busy");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException | RuntimeException | OutOfMemoryError e) {
      // 풀지 못하는 JPEG(CMYK 등)나 인코더 오류: 메타데이터만 지운 원래 파일로 저장한다
      log.warn("Image optimization failed, keeping original: {}", e.toString());
    } finally {
      if (acquired) {
        SLOTS.release();
      }
    }
    return result != null ? result : new Result(type, ImageSanitizer.sanitize(data, type));
  }

  // ---------- 다시 쓰기 ----------

  /** 다시 쓴 결과. 원래 파일(메타데이터만 지운 것)을 두는 편이 나으면 null */
  private static Result reencode(byte[] data, ImageType type, int width, int height, Limit limit)
      throws IOException {
    int orientation = type == ImageType.JPEG ? orientation(data) : 1;
    boolean swap = orientation >= 5;
    int shownW = swap ? height : width;
    int shownH = swap ? width : height;
    double scale =
        Math.min(
            1.0, Math.min((double) limit.maxWidth / shownW, (double) limit.maxHeight / shownH));
    int targetShownW = Math.max(1, (int) Math.round(shownW * scale));
    int targetShownH = Math.max(1, (int) Math.round(shownH * scale));
    boolean resized = scale < 1.0;

    BufferedImage image = decode(data, type, width, height, scale);
    if (image == null) {
      return null;
    }
    image = normalize(image);
    int targetW = swap ? targetShownH : targetShownW;
    int targetH = swap ? targetShownW : targetShownH;
    if (image.getWidth() != targetW || image.getHeight() != targetH) {
      image = resize(image, targetW, targetH);
    }
    image = orient(image, orientation);

    byte[] out;
    ImageType outType;
    if (WEBP_AVAILABLE && Math.max(image.getWidth(), image.getHeight()) <= WEBP_MAX_DIMENSION) {
      outType = ImageType.WEBP;
      // 원래 PNG가 화소당 1바이트를 넘으면 사진 같은 그림이다. 무손실은 느리고 크게 나오므로 바로 손실 압축한다
      boolean photoLike =
          (double) data.length / ((long) width * height) > LOSSLESS_MAX_BYTES_PER_PIXEL;
      if (type == ImageType.PNG && photoLike) {
        out = webp(image, false, GRAPHIC_LOSSY_QUALITY);
      } else if (type == ImageType.PNG) {
        out = webp(image, true, 1f);
        double perPixel = (double) out.length / ((long) image.getWidth() * image.getHeight());
        if (perPixel > LOSSLESS_MAX_BYTES_PER_PIXEL) {
          byte[] lossy = webp(image, false, GRAPHIC_LOSSY_QUALITY);
          if (lossy.length < out.length) {
            out = lossy;
          }
        }
      } else {
        out = webp(image, false, PHOTO_QUALITY);
      }
    } else if (type == ImageType.PNG) {
      outType = ImageType.PNG;
      out = png(image);
    } else {
      outType = ImageType.JPEG;
      out = jpeg(image, 0.85f);
    }

    if (!resized && orientation == 1) {
      // 돌릴 필요가 없으면 메타데이터 지우기는 조각만 빼므로 싸다
      byte[] sanitized = ImageSanitizer.sanitize(data, type);
      if (out.length >= sanitized.length * MIN_SAVING_RATIO) {
        return new Result(type, sanitized);
      }
    }
    return new Result(outType, out);
  }

  private static BufferedImage decode(
      byte[] data, ImageType type, int width, int height, double scale) throws IOException {
    Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(formatName(type));
    if (!readers.hasNext()) {
      return null;
    }
    ImageReader reader = readers.next();
    try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
      reader.setInput(in, true, true);
      ImageReadParam param = reader.getDefaultReadParam();
      if ((long) width * height > SUBSAMPLE_ABOVE_PIXELS) {
        // 줄일 크기의 2배 이상은 남기고 솎는다. 마지막 줄이기는 resize가 부드럽게 한다
        int step = (int) Math.floor(1.0 / (scale * 2));
        if (step >= 2) {
          param.setSourceSubsampling(step, step, 0, 0);
        }
      }
      return reader.read(0, param);
    } finally {
      reader.dispose();
    }
  }

  /** 투명도가 있으면 INT_ARGB, 없으면 INT_RGB로 맞춘다. 인코더와 줄이기가 이 두 형식을 바로 다룬다. */
  static BufferedImage normalize(BufferedImage src) {
    boolean alpha = src.getColorModel().hasAlpha();
    int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
    if (src.getType() == type) {
      return src;
    }
    BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), type);
    Graphics2D g = dst.createGraphics();
    try {
      g.setComposite(AlphaComposite.Src);
      g.drawImage(src, 0, 0, null);
    } finally {
      g.dispose();
    }
    return dst;
  }

  /** 반씩 여러 번 줄인 뒤 마지막에 목표 크기로 맞춘다. 한 번에 크게 줄이면 계단·물결 무늬가 생긴다. */
  static BufferedImage resize(BufferedImage src, int targetW, int targetH) {
    boolean alpha = src.getTransparency() != Transparency.OPAQUE;
    // 투명한 그림은 미리 곱한(premultiplied) 형식으로 줄여야 가장자리가 검게 번지지 않는다
    int workType = alpha ? BufferedImage.TYPE_INT_ARGB_PRE : BufferedImage.TYPE_INT_RGB;
    BufferedImage current = src;
    int w = src.getWidth();
    int h = src.getHeight();
    do {
      w = w / 2 >= targetW ? w / 2 : targetW;
      h = h / 2 >= targetH ? h / 2 : targetH;
      if (w < targetW) {
        w = targetW;
      }
      if (h < targetH) {
        h = targetH;
      }
      BufferedImage next = new BufferedImage(w, h, workType);
      Graphics2D g = next.createGraphics();
      try {
        g.setComposite(AlphaComposite.Src);
        g.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(current, 0, 0, w, h, null);
      } finally {
        g.dispose();
      }
      current = next;
    } while (w != targetW || h != targetH);
    return normalize(current);
  }

  /** EXIF 회전 정보(1~8)대로 세운다. 90도 단위 회전·뒤집기라 화소를 섞지 않는다. */
  static BufferedImage orient(BufferedImage src, int orientation) {
    if (orientation < 2 || orientation > 8) {
      return src;
    }
    int w = src.getWidth();
    int h = src.getHeight();
    AffineTransform t =
        switch (orientation) {
          case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0); // 좌우 뒤집기
          case 3 -> new AffineTransform(-1, 0, 0, -1, w, h); // 180도
          case 4 -> new AffineTransform(1, 0, 0, -1, 0, h); // 위아래 뒤집기
          case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0); // 대각선 뒤집기
          case 6 -> new AffineTransform(0, 1, -1, 0, h, 0); // 시계 방향 90도
          case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
          default -> new AffineTransform(0, -1, 1, 0, 0, w); // 8: 반시계 방향 90도
        };
    boolean swap = orientation >= 5;
    BufferedImage dst = new BufferedImage(swap ? h : w, swap ? w : h, src.getType());
    new AffineTransformOp(t, AffineTransformOp.TYPE_NEAREST_NEIGHBOR).filter(src, dst);
    return dst;
  }

  // ---------- 쓰기 ----------

  private static byte[] webp(BufferedImage image, boolean lossless, float quality)
      throws IOException {
    ImageWriter writer = new WebPImageWriterSpi().createWriterInstance(null);
    try {
      WebPWriteParam param = (WebPWriteParam) writer.getDefaultWriteParam();
      param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      param.setCompressionType(lossless ? CompressionType.Lossless : CompressionType.Lossy);
      param.setCompressionQuality(quality);
      param.setMethod(4);
      return write(writer, image, param);
    } finally {
      writer.dispose();
    }
  }

  private static byte[] jpeg(BufferedImage image, float quality) throws IOException {
    ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
    try {
      ImageWriteParam param = writer.getDefaultWriteParam();
      param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      param.setCompressionQuality(quality);
      param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
      BufferedImage rgb = image.getType() == BufferedImage.TYPE_INT_RGB ? image : flatten(image);
      return write(writer, rgb, param);
    } finally {
      writer.dispose();
    }
  }

  private static byte[] png(BufferedImage image) throws IOException {
    ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
    try {
      return write(writer, image, writer.getDefaultWriteParam());
    } finally {
      writer.dispose();
    }
  }

  private static byte[] write(ImageWriter writer, BufferedImage image, ImageWriteParam param)
      throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
      writer.setOutput(ios);
      writer.write(null, new IIOImage(image, null, null), param);
    }
    return out.toByteArray();
  }

  private static BufferedImage flatten(BufferedImage src) {
    BufferedImage rgb =
        new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
    Graphics2D g = rgb.createGraphics();
    try {
      g.setColor(java.awt.Color.WHITE);
      g.fillRect(0, 0, src.getWidth(), src.getHeight());
      g.drawImage(src, 0, 0, null);
    } finally {
      g.dispose();
    }
    return rgb;
  }

  // ---------- 머리 읽기 ----------

  /** 그림을 풀지 않고 파일 머리에서 가로·세로를 읽는다. */
  static long[] dimensions(byte[] data, ImageType type) {
    return switch (type) {
      case GIF -> {
        if (data.length < 10) {
          throw new IllegalArgumentException("Broken GIF");
        }
        // 논리 화면 크기. 각 장면은 이 안에 그려진다
        yield new long[] {u16le(data, 6), u16le(data, 8)};
      }
      case WEBP -> webpDimensions(data);
      default -> headerDimensions(data, type);
    };
  }

  private static long[] headerDimensions(byte[] data, ImageType type) {
    Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(formatName(type));
    if (!readers.hasNext()) {
      throw new IllegalStateException("No reader for " + type);
    }
    ImageReader reader = readers.next();
    try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
      reader.setInput(in, true, true);
      return new long[] {reader.getWidth(0), reader.getHeight(0)};
    } catch (IOException | RuntimeException e) {
      throw new IllegalArgumentException("Broken image", e);
    } finally {
      reader.dispose();
    }
  }

  static long[] webpDimensions(byte[] d) {
    if (d.length < 30) {
      throw new IllegalArgumentException("Broken WebP");
    }
    String chunk = new String(d, 12, 4, StandardCharsets.ISO_8859_1);
    switch (chunk) {
      case "VP8X" -> {
        return new long[] {u24le(d, 24) + 1L, u24le(d, 27) + 1L};
      }
      case "VP8 " -> {
        // 프레임 머리 3바이트 + 시작 코드 9d 01 2a 다음에 14비트 가로·세로
        if ((d[23] & 0xFF) != 0x9D || (d[24] & 0xFF) != 0x01 || (d[25] & 0xFF) != 0x2A) {
          throw new IllegalArgumentException("Broken WebP");
        }
        return new long[] {u16le(d, 26) & 0x3FFF, u16le(d, 28) & 0x3FFF};
      }
      case "VP8L" -> {
        if ((d[20] & 0xFF) != 0x2F) {
          throw new IllegalArgumentException("Broken WebP");
        }
        long bits = ByteBuffer.wrap(d, 21, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
        return new long[] {(bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1};
      }
      default -> throw new IllegalArgumentException("Broken WebP");
    }
  }

  /** acTL 조각이 IDAT보다 앞에 있으면 움직이는 PNG다. */
  static boolean isAnimatedPng(byte[] data, ImageType type) {
    if (type != ImageType.PNG) {
      return false;
    }
    int i = 8;
    while (i + 8 <= data.length) {
      int length = ByteBuffer.wrap(data, i, 4).getInt();
      String chunk = new String(data, i + 4, 4, StandardCharsets.ISO_8859_1);
      if (chunk.equals("acTL")) {
        return true;
      }
      if (chunk.equals("IDAT") || length < 0) {
        return false;
      }
      i += 12 + length;
    }
    return false;
  }

  private static int orientation(byte[] data) {
    try {
      Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(data));
      ExifIFD0Directory dir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
      if (dir != null && dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
        return dir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
      }
    } catch (Exception e) {
      // 읽지 못하면 돌리지 않는다
    }
    return 1;
  }

  // ---------- 준비 ----------

  private static boolean initWebp() {
    // 남이 올린 WebP를 libwebp로 풀 일은 없게 읽기 플러그인은 뺀다 (쓰기만 쓴다)
    IIORegistry registry = IIORegistry.getDefaultInstance();
    ImageReaderSpi reader = registry.getServiceProviderByClass(WebPImageReaderSpi.class);
    if (reader != null) {
      registry.deregisterServiceProvider(reader, ImageReaderSpi.class);
    }
    try {
      BufferedImage probe = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
      byte[] out = webp(probe, false, PHOTO_QUALITY);
      if (ImageType.bySignature(out).orElse(null) != ImageType.WEBP) {
        throw new IOException("Unexpected WebP output");
      }
      return true;
    } catch (Throwable e) { // UnsatisfiedLinkError 등: 이 OS·CPU용 libwebp가 없다
      log.warn("WebP encoder unavailable, falling back to JPEG/PNG: {}", e.toString());
      return false;
    }
  }

  private static String formatName(ImageType type) {
    return switch (type) {
      case JPEG -> "jpeg";
      case PNG -> "png";
      case GIF -> "gif";
      case WEBP -> "webp";
    };
  }

  private static int u16le(byte[] d, int i) {
    return (d[i] & 0xFF) | (d[i + 1] & 0xFF) << 8;
  }

  private static int u24le(byte[] d, int i) {
    return (d[i] & 0xFF) | (d[i + 1] & 0xFF) << 8 | (d[i + 2] & 0xFF) << 16;
  }
}
