package com.blog.board.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/** 테스트용 작은 이미지 만들기. */
public final class TestImages {

  private TestImages() {}

  public static byte[] png() {
    return write("png");
  }

  public static byte[] jpeg() {
    return write("jpg");
  }

  /** JPEG 앞부분(SOI 바로 뒤)에 EXIF APP1 구간을 끼워 넣는다. */
  public static byte[] jpegWithExif(String secret) {
    byte[] jpeg = jpeg();
    byte[] payload = ("Exif\0\0" + secret).getBytes(StandardCharsets.ISO_8859_1);
    int length = payload.length + 2;
    byte[] app1 = new byte[4 + payload.length];
    app1[0] = (byte) 0xFF;
    app1[1] = (byte) 0xE1;
    app1[2] = (byte) (length >> 8);
    app1[3] = (byte) length;
    System.arraycopy(payload, 0, app1, 4, payload.length);
    byte[] out = new byte[jpeg.length + app1.length];
    System.arraycopy(jpeg, 0, out, 0, 2);
    System.arraycopy(app1, 0, out, 2, app1.length);
    System.arraycopy(jpeg, 2, out, 2 + app1.length, jpeg.length - 2);
    return out;
  }

  /** PNG의 IEND 앞에 tEXt 조각을 끼워 넣는다. */
  public static byte[] pngWithText(String text) {
    byte[] png = png();
    byte[] data = ("Comment\0" + text).getBytes(StandardCharsets.ISO_8859_1);
    ByteBuffer chunk = ByteBuffer.allocate(12 + data.length);
    chunk.putInt(data.length);
    byte[] typeAndData = new byte[4 + data.length];
    System.arraycopy("tEXt".getBytes(StandardCharsets.ISO_8859_1), 0, typeAndData, 0, 4);
    System.arraycopy(data, 0, typeAndData, 4, data.length);
    chunk.put(typeAndData);
    CRC32 crc = new CRC32();
    crc.update(typeAndData);
    chunk.putInt((int) crc.getValue());
    int iend = png.length - 12;
    byte[] out = new byte[png.length + chunk.capacity()];
    System.arraycopy(png, 0, out, 0, iend);
    System.arraycopy(chunk.array(), 0, out, iend, chunk.capacity());
    System.arraycopy(png, iend, out, iend + chunk.capacity(), 12);
    return out;
  }

  /** PNG의 IHDR 바로 뒤(IDAT 앞)에 조각 하나를 끼워 넣는다. CRC도 맞춘다. */
  public static byte[] pngWithChunk(byte[] png, String type, byte[] data) {
    int afterIhdr = 8 + 12 + 13;
    byte[] chunk = pngChunk(type, data);
    byte[] out = new byte[png.length + chunk.length];
    System.arraycopy(png, 0, out, 0, afterIhdr);
    System.arraycopy(chunk, 0, out, afterIhdr, chunk.length);
    System.arraycopy(png, afterIhdr, out, afterIhdr + chunk.length, png.length - afterIhdr);
    return out;
  }

  /** 조각들을 RIFF/WEBP 머리로 감싼다. */
  public static byte[] webpFile(byte[]... chunks) {
    int size = 4;
    for (byte[] c : chunks) {
      size += c.length;
    }
    ByteBuffer b = ByteBuffer.allocate(8 + size).order(ByteOrder.LITTLE_ENDIAN);
    b.put("RIFF".getBytes(StandardCharsets.ISO_8859_1));
    b.putInt(size);
    b.put("WEBP".getBytes(StandardCharsets.ISO_8859_1));
    for (byte[] c : chunks) {
      b.put(c);
    }
    return b.array();
  }

  /** WebP 조각 하나 (홀수 길이면 채움 바이트 포함). */
  public static byte[] webpChunk(String type, byte[] data) {
    return chunk(type, data);
  }

  /** VP8X + 가짜 VP8L + EXIF 조각으로 된 WebP 모양 파일. 실제 그림은 아니지만 구조 검사에는 충분하다. */
  public static byte[] webpWithExif(String secret) {
    byte[] vp8x = chunk("VP8X", new byte[] {0x08, 0, 0, 0, 0, 0, 0, 0, 0, 0});
    byte[] vp8l = chunk("VP8L", new byte[] {0x2F, 0, 0, 0, 0});
    byte[] exif = chunk("EXIF", secret.getBytes(StandardCharsets.ISO_8859_1));
    int size = 4 + vp8x.length + vp8l.length + exif.length;
    ByteBuffer b = ByteBuffer.allocate(8 + size).order(ByteOrder.LITTLE_ENDIAN);
    b.put("RIFF".getBytes(StandardCharsets.ISO_8859_1));
    b.putInt(size);
    b.put("WEBP".getBytes(StandardCharsets.ISO_8859_1));
    b.put(vp8x).put(vp8l).put(exif);
    return b.array();
  }

  /** 사진처럼 보이는 그림: 부드러운 색 변화 + 잔무늬(노이즈) + 도형. 같은 seed면 같은 그림. */
  public static BufferedImage photo(int width, int height, long seed) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    java.util.Random random = new java.util.Random(seed);
    double fx = 2 * Math.PI / width;
    double fy = 2 * Math.PI / height;
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        int n = (int) (random.nextGaussian() * 6);
        int r = clamp(120 + (int) (80 * Math.sin(x * fx * 1.3 + seed)) + n);
        int g = clamp(110 + (int) (70 * Math.cos(y * fy * 1.7)) + n);
        int b = clamp(100 + (int) (60 * Math.sin((x + y) * fx * 0.9)) + n);
        image.setRGB(x, y, r << 16 | g << 8 | b);
      }
    }
    java.awt.Graphics2D g = image.createGraphics();
    g.setRenderingHint(
        java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
    for (int i = 0; i < 25; i++) {
      g.setColor(new java.awt.Color(random.nextInt(0x1000000)));
      int s = Math.max(4, Math.min(width, height) / 6);
      g.fillOval(random.nextInt(width), random.nextInt(height), s, s * 2 / 3);
    }
    g.dispose();
    return image;
  }

  private static int clamp(int v) {
    return Math.max(0, Math.min(255, v));
  }

  public static byte[] encode(BufferedImage image, String format) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      if (!ImageIO.write(image, format, out)) {
        throw new IllegalStateException("No writer for " + format);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  /** 휴대폰 사진처럼 JPEG를 고품질로 쓴다. */
  public static byte[] jpeg(BufferedImage image, float quality) {
    javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
      writer.setOutput(ios);
      javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
      param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
      param.setCompressionQuality(quality);
      writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      writer.dispose();
    }
    return out.toByteArray();
  }

  /**
   * 진짜 EXIF(TIFF) 구간을 JPEG에 넣는다: IFD0에 회전(Orientation)·설명(ImageDescription), GPS IFD에 위도·경도. 설명 칸에
   * {@code marker}를 적어 결과에 남았는지 찾을 수 있게 한다.
   */
  public static byte[] withExif(byte[] jpeg, int orientation, String marker) {
    byte[] desc = (marker + "\0").getBytes(StandardCharsets.ISO_8859_1);
    // TIFF: 머리 8 + IFD0(3칸) 2+36+4 = 50 → GPS IFD(4칸) 2+48+4 = 54 → 위도·경도 유리수 각 24 → 설명
    int ifd0 = 8;
    int gpsIfd = ifd0 + 2 + 3 * 12 + 4;
    int lat = gpsIfd + 2 + 4 * 12 + 4;
    int lon = lat + 24;
    int descAt = lon + 24;
    ByteBuffer t = ByteBuffer.allocate(descAt + desc.length).order(ByteOrder.BIG_ENDIAN);
    t.put(new byte[] {'M', 'M', 0, 42}).putInt(ifd0);
    t.putShort((short) 3);
    t.putShort((short) 0x010E).putShort((short) 2).putInt(desc.length).putInt(descAt);
    t.putShort((short) 0x0112)
        .putShort((short) 3)
        .putInt(1)
        .putShort((short) orientation)
        .putShort((short) 0);
    t.putShort((short) 0x8825).putShort((short) 4).putInt(1).putInt(gpsIfd);
    t.putInt(0);
    t.putShort((short) 4);
    t.putShort((short) 1).putShort((short) 2).putInt(2).put(new byte[] {'N', 0, 0, 0});
    t.putShort((short) 2).putShort((short) 5).putInt(3).putInt(lat);
    t.putShort((short) 3).putShort((short) 2).putInt(2).put(new byte[] {'E', 0, 0, 0});
    t.putShort((short) 4).putShort((short) 5).putInt(3).putInt(lon);
    t.putInt(0);
    t.putInt(37).putInt(1).putInt(33).putInt(1).putInt(5940).putInt(100); // 37°33'59.40"
    t.putInt(126).putInt(1).putInt(58).putInt(1).putInt(4080).putInt(100); // 126°58'40.80"
    t.put(desc);
    byte[] tiff = t.array();
    byte[] payload = new byte[6 + tiff.length];
    System.arraycopy("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1), 0, payload, 0, 6);
    System.arraycopy(tiff, 0, payload, 6, tiff.length);
    int length = payload.length + 2;
    byte[] out = new byte[jpeg.length + 4 + payload.length];
    out[0] = (byte) 0xFF;
    out[1] = (byte) 0xD8;
    out[2] = (byte) 0xFF;
    out[3] = (byte) 0xE1;
    out[4] = (byte) (length >> 8);
    out[5] = (byte) length;
    System.arraycopy(payload, 0, out, 6, payload.length);
    System.arraycopy(jpeg, 2, out, 6 + payload.length, jpeg.length - 2);
    return out;
  }

  /** 왼쪽 절반은 반투명한 색 변화, 오른쪽 절반은 완전히 투명한 PNG. */
  public static byte[] pngWithAlpha(int width, int height) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width / 2; x++) {
        int a = 64 + (y * 191 / height);
        image.setRGB(x, y, a << 24 | (x * 255 / width) << 16 | 0x40 << 8 | 0xC0);
      }
    }
    return encode(image, "png");
  }

  /** 장면 3개짜리 움직이는 GIF. */
  public static byte[] animatedGif() {
    javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
      writer.setOutput(ios);
      writer.prepareWriteSequence(null);
      int[] colors = {0xFF0000, 0x00FF00, 0x0000FF};
      for (int color : colors) {
        BufferedImage frame = new BufferedImage(40, 30, BufferedImage.TYPE_BYTE_INDEXED);
        java.awt.Graphics2D g = frame.createGraphics();
        g.setColor(new java.awt.Color(color));
        g.fillRect(0, 0, 40, 30);
        g.dispose();
        writer.writeToSequence(new javax.imageio.IIOImage(frame, null, null), null);
      }
      writer.endWriteSequence();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      writer.dispose();
    }
    return out.toByteArray();
  }

  /** 머리(IHDR)만 그럴듯하고 그림 데이터는 엉터리인 PNG. 가로·세로가 크면 압축 폭탄처럼 보인다. 풀기 전에 막는지 확인할 때 쓴다(풀면 실패한다). */
  public static byte[] pngHeader(int width, int height) {
    ByteBuffer ihdr = ByteBuffer.allocate(13);
    ihdr.putInt(width).putInt(height).put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0);
    ihdr.put((byte) 0);
    byte[] out = new byte[8 + 25 + 12 + 4 + 12];
    ByteBuffer b = ByteBuffer.wrap(out);
    b.put(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
    b.put(pngChunk("IHDR", ihdr.array()));
    b.put(pngChunk("IDAT", new byte[] {1, 2, 3, 4}));
    b.put(pngChunk("IEND", new byte[0]));
    return out;
  }

  private static byte[] pngChunk(String type, byte[] data) {
    byte[] typeAndData = new byte[4 + data.length];
    System.arraycopy(type.getBytes(StandardCharsets.ISO_8859_1), 0, typeAndData, 0, 4);
    System.arraycopy(data, 0, typeAndData, 4, data.length);
    CRC32 crc = new CRC32();
    crc.update(typeAndData);
    ByteBuffer b = ByteBuffer.allocate(12 + data.length);
    b.putInt(data.length).put(typeAndData).putInt((int) crc.getValue());
    return b.array();
  }

  /** 서버가 쓴 WebP를 풀어 본다. 서버는 WebP 읽기 플러그인을 빼 두므로 직접 만든다. */
  public static BufferedImage readWebp(byte[] webp) {
    javax.imageio.ImageReader reader =
        new com.luciad.imageio.webp.WebPImageReaderSpi().createReaderInstance(null);
    try (javax.imageio.stream.ImageInputStream in =
        ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(webp))) {
      reader.setInput(in);
      return reader.read(0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      reader.dispose();
    }
  }

  private static byte[] chunk(String type, byte[] data) {
    int padded = data.length + (data.length & 1);
    ByteBuffer b = ByteBuffer.allocate(8 + padded).order(ByteOrder.LITTLE_ENDIAN);
    b.put(type.getBytes(StandardCharsets.ISO_8859_1));
    b.putInt(data.length);
    b.put(data);
    return b.array();
  }

  private static byte[] write(String format) {
    BufferedImage image = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
    image.setRGB(0, 0, 0xFF0000);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      ImageIO.write(image, format, out);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }
}
