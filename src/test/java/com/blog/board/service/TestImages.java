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
