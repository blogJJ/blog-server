package com.blog.board.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
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
import java.util.Set;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * 이미지의 EXIF 등 메타데이터를 지운다 (T062, BRD-05). 사진에 들어 있는 촬영 위치(GPS)·기기 정보가 남에게 보이지 않게 한다.
 *
 * <ul>
 *   <li>JPEG: APP1(EXIF·XMP)·APP13(IPTC) 구간을 뺀다. 휴대폰 사진처럼 회전 정보가 있으면 지우기 전에 그림을 돌려서 다시 저장한다. 회전 정보를
 *       지우면 옆으로 누운 사진이 되기 때문이다.
 *   <li>PNG: eXIf·tEXt·zTXt·iTXt·tIME 조각을 뺀다.
 *   <li>WebP: EXIF·XMP 조각을 빼고 VP8X 표시와 전체 길이를 고친다.
 *   <li>GIF: 위치 정보를 담는 표준 칸이 없어 그대로 둔다.
 * </ul>
 */
public final class ImageSanitizer {

  private static final Set<String> PNG_DROP = Set.of("eXIf", "tEXt", "zTXt", "iTXt", "tIME");

  private ImageSanitizer() {}

  public static byte[] sanitize(byte[] data, ImageType type) {
    return switch (type) {
      case JPEG -> jpeg(data);
      case PNG -> png(data);
      case WEBP -> webp(data);
      case GIF -> data;
    };
  }

  // ---------- JPEG ----------

  static byte[] jpeg(byte[] data) {
    int orientation = orientation(data);
    if (orientation == 3 || orientation == 6 || orientation == 8) {
      byte[] rotated = rotateJpeg(data, orientation);
      if (rotated != null) {
        return stripJpeg(rotated); // ImageIO가 새로 쓴 파일에는 JFIF만 있지만 한 번 더 거른다
      }
    }
    return stripJpeg(data);
  }

  private static byte[] stripJpeg(byte[] data) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
    out.write(0xFF);
    out.write(0xD8);
    int i = 2;
    while (i + 4 <= data.length) {
      if ((data[i] & 0xFF) != 0xFF) {
        throw new IllegalArgumentException("Broken JPEG");
      }
      int marker = data[i + 1] & 0xFF;
      if (marker == 0xFF) { // 채움 바이트
        i++;
        continue;
      }
      if (marker == 0xDA) { // 영상 데이터 시작: 나머지는 그대로
        out.write(data, i, data.length - i);
        return out.toByteArray();
      }
      if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
        out.write(data, i, 2);
        i += 2;
        continue;
      }
      int length = ((data[i + 2] & 0xFF) << 8) | (data[i + 3] & 0xFF);
      if (length < 2 || i + 2 + length > data.length) {
        throw new IllegalArgumentException("Broken JPEG");
      }
      boolean drop = marker == 0xE1 || marker == 0xED;
      if (!drop) {
        out.write(data, i, 2 + length);
      }
      i += 2 + length;
    }
    throw new IllegalArgumentException("Broken JPEG");
  }

  private static int orientation(byte[] data) {
    try {
      Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(data));
      ExifIFD0Directory dir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
      if (dir != null && dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
        return dir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
      }
    } catch (Exception e) {
      // 읽지 못하면 회전 없이 메타데이터만 지운다
    }
    return 1;
  }

  /** 회전 정보대로 돌려 JPEG로 다시 쓴다. CMYK처럼 ImageIO가 못 읽으면 null. */
  private static byte[] rotateJpeg(byte[] data, int orientation) {
    try {
      BufferedImage src = ImageIO.read(new ByteArrayInputStream(data));
      if (src == null) {
        return null;
      }
      int w = src.getWidth();
      int h = src.getHeight();
      AffineTransform t = new AffineTransform();
      int nw = w;
      int nh = h;
      switch (orientation) {
        case 3 -> {
          t.translate(w, h);
          t.rotate(Math.PI);
        }
        case 6 -> {
          nw = h;
          nh = w;
          t.translate(h, 0);
          t.rotate(Math.PI / 2);
        }
        default -> { // 8
          nw = h;
          nh = w;
          t.translate(0, w);
          t.rotate(-Math.PI / 2);
        }
      }
      BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
      new AffineTransformOp(t, AffineTransformOp.TYPE_BICUBIC).filter(toRgb(src), dst);

      Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
      if (!writers.hasNext()) {
        return null;
      }
      ImageWriter writer = writers.next();
      ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
      try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
        writer.setOutput(ios);
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.92f);
        writer.write(null, new IIOImage(dst, null, null), param);
      } finally {
        writer.dispose();
      }
      return out.toByteArray();
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  private static BufferedImage toRgb(BufferedImage src) {
    if (src.getType() == BufferedImage.TYPE_INT_RGB) {
      return src;
    }
    BufferedImage rgb =
        new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
    rgb.getGraphics().drawImage(src, 0, 0, null);
    return rgb;
  }

  // ---------- PNG ----------

  static byte[] png(byte[] data) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
    out.write(data, 0, 8);
    int i = 8;
    while (i + 12 <= data.length) {
      int length = ByteBuffer.wrap(data, i, 4).getInt();
      if (length < 0 || i + 12L + length > data.length) {
        throw new IllegalArgumentException("Broken PNG");
      }
      String chunk = new String(data, i + 4, 4, StandardCharsets.ISO_8859_1);
      if (!PNG_DROP.contains(chunk)) {
        out.write(data, i, 12 + length);
      }
      i += 12 + length;
      if (chunk.equals("IEND")) {
        return out.toByteArray();
      }
    }
    throw new IllegalArgumentException("Broken PNG");
  }

  // ---------- WebP ----------

  static byte[] webp(byte[] data) {
    ByteArrayOutputStream body = new ByteArrayOutputStream(data.length);
    int i = 12;
    while (i + 8 <= data.length) {
      String chunk = new String(data, i, 4, StandardCharsets.ISO_8859_1);
      int size = ByteBuffer.wrap(data, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
      int padded = size + (size & 1);
      if (size < 0 || i + 8L + padded > data.length) {
        // 마지막 조각의 채움 바이트가 빠진 파일도 있다
        if (size >= 0 && i + 8L + size == data.length) {
          padded = size;
        } else {
          throw new IllegalArgumentException("Broken WebP");
        }
      }
      if (!chunk.equals("EXIF") && !chunk.equals("XMP ")) {
        int start = body.size();
        body.write(data, i, 8 + padded);
        if (chunk.equals("VP8X") && size >= 1) {
          byte[] written = body.toByteArray();
          written[start + 8] = (byte) (written[start + 8] & ~0x0C); // EXIF(0x08)·XMP(0x04) 표시 끄기
          body.reset();
          body.write(written, 0, written.length);
        }
      }
      i += 8 + padded;
    }
    byte[] chunks = body.toByteArray();
    ByteBuffer out = ByteBuffer.allocate(12 + chunks.length).order(ByteOrder.LITTLE_ENDIAN);
    out.put(data, 0, 4); // RIFF
    out.putInt(4 + chunks.length);
    out.put(data, 8, 4); // WEBP
    out.put(chunks);
    return out.array();
  }
}
