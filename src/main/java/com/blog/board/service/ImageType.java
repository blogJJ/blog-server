package com.blog.board.service;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** 올릴 수 있는 이미지 종류 (BRD-05, SEC-08). svg·동영상·문서·압축 파일은 받지 않는다. */
public enum ImageType {
  JPEG("image/jpeg", "jpg", Set.of("jpg", "jpeg")),
  PNG("image/png", "png", Set.of("png")),
  GIF("image/gif", "gif", Set.of("gif")),
  WEBP("image/webp", "webp", Set.of("webp"));

  private final String mimeType;
  private final String storedExtension;
  private final Set<String> extensions;

  ImageType(String mimeType, String storedExtension, Set<String> extensions) {
    this.mimeType = mimeType;
    this.storedExtension = storedExtension;
    this.extensions = extensions;
  }

  public String mimeType() {
    return mimeType;
  }

  /** 저장할 때 붙이는 확장자 */
  public String storedExtension() {
    return storedExtension;
  }

  public static Optional<ImageType> byExtension(String extension) {
    String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
    return Arrays.stream(values()).filter(t -> t.extensions.contains(ext)).findFirst();
  }

  /** 파일 앞부분(시그니처)으로 실제 종류를 알아낸다. */
  public static Optional<ImageType> bySignature(byte[] b) {
    if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
      return Optional.of(JPEG);
    }
    if (b.length >= 8
        && (b[0] & 0xFF) == 0x89
        && b[1] == 'P'
        && b[2] == 'N'
        && b[3] == 'G'
        && b[4] == 0x0D
        && b[5] == 0x0A
        && b[6] == 0x1A
        && b[7] == 0x0A) {
      return Optional.of(PNG);
    }
    if (b.length >= 6
        && b[0] == 'G'
        && b[1] == 'I'
        && b[2] == 'F'
        && b[3] == '8'
        && (b[4] == '7' || b[4] == '9')
        && b[5] == 'a') {
      return Optional.of(GIF);
    }
    if (b.length >= 12
        && b[0] == 'R'
        && b[1] == 'I'
        && b[2] == 'F'
        && b[3] == 'F'
        && b[8] == 'W'
        && b[9] == 'E'
        && b[10] == 'B'
        && b[11] == 'P') {
      return Optional.of(WEBP);
    }
    return Optional.empty();
  }

  /** 저장 이름의 확장자로 응답 Content-Type을 정한다. */
  public static Optional<ImageType> byStoredName(String storedName) {
    int dot = storedName.lastIndexOf('.');
    return dot < 0 ? Optional.empty() : byExtension(storedName.substring(dot + 1));
  }
}
