package com.blog.board.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 올릴 수 있는 이미지 종류 (BRD-05, SEC-08). 확장자와 파일 앞부분(시그니처)으로 종류를 가린다. */
class ImageTypeTest {

  @ParameterizedTest
  @ValueSource(strings = {"jpg", "JPG", "jpeg", "JpEg", "png", "PNG", "gif", "webp", "WEBP"})
  @DisplayName("jpg·jpeg·png·gif·webp 확장자는 대소문자와 상관없이 받는다")
  void allowedExtensionsInAnyCase(String extension) {
    assertThat(ImageType.byExtension(extension)).isPresent();
  }

  @ParameterizedTest
  @ValueSource(strings = {"svg", "bmp", "tiff", "heic", "mp4", "pdf", "zip", "jpg.exe", ""})
  @DisplayName("svg·동영상·문서·압축 등 다른 확장자는 받지 않는다")
  void otherExtensionsAreRejected(String extension) {
    assertThat(ImageType.byExtension(extension)).isEmpty();
  }

  @Test
  @DisplayName("jpeg로 올려도 저장 확장자는 jpg 하나로 맞춘다")
  void jpegIsStoredAsJpg() {
    assertThat(ImageType.byExtension("jpeg").orElseThrow().storedExtension()).isEqualTo("jpg");
    assertThat(ImageType.byStoredName("3f2a.jpg")).contains(ImageType.JPEG);
    assertThat(ImageType.byStoredName("3f2a.webp").orElseThrow().mimeType())
        .isEqualTo("image/webp");
  }

  @Test
  @DisplayName("저장 이름에 확장자가 없거나 모르는 확장자면 Content-Type을 정하지 않는다")
  void storedNameWithoutKnownExtension() {
    assertThat(ImageType.byStoredName("no-extension")).isEmpty();
    assertThat(ImageType.byExtension(null)).isEmpty();
    assertThat(ImageType.byStoredName("evil.svg")).isEmpty();
    assertThat(ImageType.byStoredName("a.png.html")).isEmpty();
  }

  @Test
  @DisplayName("실제 파일 시그니처로 네 종류를 알아낸다 (GIF87a·GIF89a 모두)")
  void realSignatures() {
    assertThat(ImageType.bySignature(TestImages.jpeg())).contains(ImageType.JPEG);
    assertThat(ImageType.bySignature(TestImages.png())).contains(ImageType.PNG);
    assertThat(ImageType.bySignature(TestImages.animatedGif())).contains(ImageType.GIF);
    assertThat(ImageType.bySignature(ascii("GIF87a..."))).contains(ImageType.GIF);
    assertThat(ImageType.bySignature(TestImages.webpWithExif("x"))).contains(ImageType.WEBP);
  }

  @Test
  @DisplayName("비슷하지만 다른 시그니처는 이미지로 보지 않는다")
  void nearMissSignaturesAreRejected() {
    // RIFF 묶음이지만 WebP가 아닌 소리 파일
    assertThat(ImageType.bySignature(ascii("RIFF\0\0\0\0WAVEfmt "))).isEmpty();
    // 없는 GIF 판, 잘못된 끝 글자
    assertThat(ImageType.bySignature(ascii("GIF88a"))).isEmpty();
    assertThat(ImageType.bySignature(ascii("GIF89b"))).isEmpty();
    // JPEG SOI(FF D8) 뒤에 표지가 없는 것
    assertThat(ImageType.bySignature(new byte[] {(byte) 0xFF, (byte) 0xD8, 0x00})).isEmpty();
    // 줄바꿈 변환(CRLF → LF)으로 망가진 PNG
    byte[] png = TestImages.png();
    byte[] mangled = new byte[png.length - 1];
    System.arraycopy(png, 0, mangled, 0, 4);
    System.arraycopy(png, 5, mangled, 4, png.length - 5);
    assertThat(ImageType.bySignature(mangled)).isEmpty();
    // HTML·SVG 문서
    assertThat(ImageType.bySignature(ascii("<html><body>"))).isEmpty();
  }

  @Test
  @DisplayName("시그니처보다 짧은 파일은 앞부분이 같아도 이미지로 보지 않는다")
  void truncatedSignaturesAreRejected() {
    assertThat(ImageType.bySignature(new byte[0])).isEmpty();
    assertThat(ImageType.bySignature(new byte[] {(byte) 0xFF, (byte) 0xD8})).isEmpty();
    assertThat(ImageType.bySignature(Arrays.copyOf(TestImages.png(), 7))).isEmpty();
    assertThat(ImageType.bySignature(ascii("GIF89"))).isEmpty();
    assertThat(ImageType.bySignature(ascii("RIFF\0\0\0\0WEB"))).isEmpty();
  }

  private static byte[] ascii(String s) {
    return s.getBytes(StandardCharsets.ISO_8859_1);
  }
}
