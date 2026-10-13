package com.blog.board.service;

import static com.blog.board.service.ImageSanitizerTest.contains;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.blog.auth.repository.UserRepository;
import com.blog.board.domain.PostImage;
import com.blog.board.repository.PostImageRepository;
import com.blog.common.error.BusinessException;
import com.blog.common.error.ErrorCode;
import com.blog.common.security.AccountGuard;
import com.blog.common.security.AccountGuard.Activity;
import com.blog.common.storage.FileStorage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

/** 이미지 올리기 검사 (T062, BRD-05, SEC-08, D-75). 저장소는 메모리로 바꿔 무엇이 저장됐는지 본다. */
class ImageServiceTest {

  private final MemoryStorage storage = new MemoryStorage();
  private final PostImageRepository images = mock(PostImageRepository.class);
  private final AccountGuard guard = mock(AccountGuard.class);
  private ImageService service;

  @BeforeEach
  void setUp() {
    when(images.save(any(PostImage.class))).thenAnswer(inv -> inv.getArgument(0));
    service = new ImageService(storage, images, mock(UserRepository.class), guard);
  }

  @Test
  @DisplayName("글 이미지는 EXIF를 지우고 UUID 이름으로 저장하며, 원래 이름은 경로를 뗀 파일 이름만 DB에 남긴다")
  void postImageIsCleanedAndStoredUnderUuidName() {
    byte[] jpeg = TestImages.jpegWithExif("GPS-37.5,127.0");

    ImageService.Uploaded uploaded =
        service.uploadPostImage(1L, file("C:\\Users\\kim\\Pictures\\집 앞.jpg", "image/jpeg", jpeg));

    assertThat(uploaded.storedName()).matches("[0-9a-f-]{36}\\.(jpg|webp)");
    assertThat(uploaded.url()).isEqualTo("/api/images/" + uploaded.storedName());
    byte[] stored = storage.files.get(uploaded.storedName());
    assertThat(contains(stored, "GPS-37.5")).isFalse();
    ArgumentCaptor<PostImage> saved = ArgumentCaptor.forClass(PostImage.class);
    verify(images).save(saved.capture());
    assertThat(saved.getValue().getOriginalName()).isEqualTo("집 앞.jpg");
    assertThat(saved.getValue().getStoredName()).isEqualTo(uploaded.storedName());
    assertThat(saved.getValue().getSizeBytes()).isEqualTo(stored.length);
    assertThat(saved.getValue().getContentType())
        .isEqualTo(ImageService.mimeType(uploaded.storedName()).orElseThrow());
  }

  @Test
  @DisplayName("큰 사진은 WebP로 줄여 저장하고 DB의 형식·크기도 저장한 파일 기준이다 (T162)")
  void largePhotoIsStoredAsWebp() {
    byte[] jpeg = TestImages.jpeg(TestImages.photo(2400, 1600, 21), 0.92f);

    ImageService.Uploaded uploaded =
        service.uploadPostImage(1L, file("big.jpeg", "image/jpeg", jpeg));

    assertThat(uploaded.storedName()).endsWith(".webp");
    ArgumentCaptor<PostImage> saved = ArgumentCaptor.forClass(PostImage.class);
    verify(images).save(saved.capture());
    assertThat(saved.getValue().getContentType()).isEqualTo("image/webp");
    assertThat(saved.getValue().getSizeBytes()).isLessThan(jpeg.length);
    assertThat(ImageOptimizer.webpDimensions(storage.files.get(uploaded.storedName())))
        .containsExactly(1600, 1067);
  }

  @Test
  @DisplayName("원래 이름이 255자를 넘으면 확장자가 남도록 뒤쪽 255자만 DB에 남긴다")
  void longOriginalNameKeepsTail() {
    String longName = "가".repeat(300) + ".png";

    service.uploadPostImage(1L, file(longName, "image/png", TestImages.png()));

    ArgumentCaptor<PostImage> saved = ArgumentCaptor.forClass(PostImage.class);
    verify(images).save(saved.capture());
    assertThat(saved.getValue().getOriginalName()).hasSize(255).endsWith("가.png");
  }

  @Test
  @DisplayName("정지된 계정이면 파일을 검사·저장하기 전에 막는다 (T018)")
  void suspendedAccountCannotUpload() {
    doThrow(new BusinessException(ErrorCode.ACCOUNT_SUSPENDED))
        .when(guard)
        .check(1L, Activity.WRITE_POST);

    MockMultipartFile png = file("a.png", "image/png", TestImages.png());
    assertThatThrownBy(() -> service.uploadPostImage(1L, png))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.ACCOUNT_SUSPENDED);
    assertThat(storage.files).isEmpty();
    verify(images, never()).save(any());
  }

  @Test
  @DisplayName("파일이 없거나 비어 있으면 INVALID_INPUT")
  void missingOrEmptyFileIsRejected() {
    assertError(() -> service.storeCover(null), ErrorCode.INVALID_INPUT, "이미지를 골라 주세요.");
    assertError(
        () -> service.storeCover(file("a.png", "image/png", new byte[0])),
        ErrorCode.INVALID_INPUT,
        "이미지를 골라 주세요.");
  }

  @Test
  @DisplayName("한 장이 3MB를 넘으면 내용을 보기 전에 PAYLOAD_TOO_LARGE")
  void overThreeMegabytesIsRejected() {
    byte[] big = new byte[PostImage.MAX_BYTES + 1];
    assertError(
        () -> service.storeCover(file("a.png", "image/png", big)),
        ErrorCode.PAYLOAD_TOO_LARGE,
        "이미지는 한 장에 3MB까지 올릴 수 있어요.");
  }

  @Test
  @DisplayName("jpg·jpeg·png·gif·webp가 아닌 확장자(svg, 확장자 없음 포함)는 받지 않는다")
  void otherExtensionsAreRejected() {
    String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>";
    assertNotAllowed(file("x.svg", "image/svg+xml", svg.getBytes()));
    assertNotAllowed(file("photo.bmp", "image/bmp", TestImages.png()));
    assertNotAllowed(file("photo", "image/png", TestImages.png()));
    assertNotAllowed(file("photo.png.exe", "image/png", TestImages.png()));
  }

  @Test
  @DisplayName("브라우저가 보낸 MIME이 확장자와 다르면 받지 않는다. image/jpg 별칭과 대문자는 허용한다")
  void mimeMustMatchExtension() {
    assertNotAllowed(file("photo.png", "image/gif", TestImages.png()));
    assertNotAllowed(file("photo.png", null, TestImages.png()));
    assertNotAllowed(file("photo.gif", "image/jpg", TestImages.png())); // jpg 별칭은 JPEG에만

    assertThat(service.storeProfile(file("me.jpeg", "image/jpg", TestImages.jpeg()))).isNotNull();
    assertThat(service.storeProfile(file("me.PNG", "IMAGE/PNG", TestImages.png()))).isNotNull();
  }

  @Test
  @DisplayName("확장자·MIME이 맞아도 파일 앞부분(시그니처)이 다른 종류면 받지 않는다")
  void signatureMustMatchExtension() {
    assertNotAllowed(file("photo.jpg", "image/jpeg", TestImages.png()));
    assertNotAllowed(file("photo.png", "image/png", "<html>not an image</html>".getBytes()));
    assertNotAllowed(file("photo.webp", "image/webp", "RIFF\0\0\0\0WAVEfmt ".getBytes()));
  }

  @Test
  @DisplayName("화소가 5천만을 넘는 그림은 풀기 전에 PAYLOAD_TOO_LARGE로 막는다")
  void tooManyPixelsIsPayloadTooLarge() {
    assertError(
        () -> service.storeCover(file("bomb.png", "image/png", TestImages.pngHeader(8000, 8000))),
        ErrorCode.PAYLOAD_TOO_LARGE,
        "이미지가 너무 커요. 가로×세로 5천만 화소까지 올릴 수 있어요.");
    assertThat(storage.files).isEmpty();
  }

  @Test
  @DisplayName("시그니처만 맞고 구조가 깨진 파일은 '손상' INVALID_INPUT")
  void brokenImageIsInvalidInput() {
    byte[] jpeg = TestImages.jpeg();
    byte[] truncated = Arrays.copyOf(jpeg, 20);
    assertError(
        () -> service.storeCover(file("a.jpg", "image/jpeg", truncated)),
        ErrorCode.INVALID_INPUT,
        "이미지 파일이 손상되어 올릴 수 없어요.");
    assertThat(storage.files).isEmpty();
  }

  @Test
  @DisplayName("프로필 사진은 긴 변 512px로 줄여 저장한다")
  void profileIsResizedTo512() {
    byte[] jpeg = TestImages.jpeg(TestImages.photo(1000, 1500, 22), 0.9f);

    String name = service.storeProfile(file("me.jpg", "image/jpeg", jpeg));

    assertThat(ImageOptimizer.webpDimensions(storage.files.get(name))).containsExactly(341, 512);
  }

  @Test
  @DisplayName("보여 줄 때 Content-Type은 저장 확장자로 정한다. 대표 그림 svg만 image/svg+xml")
  void mimeTypeByStoredName() {
    assertThat(ImageService.mimeType("a.jpg")).contains("image/jpeg");
    assertThat(ImageService.mimeType("a.webp")).contains("image/webp");
    assertThat(ImageService.mimeType("cover.svg")).contains("image/svg+xml");
    assertThat(ImageService.mimeType("a.html")).isEmpty();
    assertThat(ImageService.mimeType("noext")).isEmpty();
    assertThat(ImageService.mimeType(null)).isEmpty();
  }

  @Test
  @DisplayName("모르는 확장자·없는 파일·이상한 이름은 저장소를 열지 않고 NOT_FOUND")
  void openRejectsUnknownMissingOrInvalidNames() throws IOException {
    FileStorage files = mock(FileStorage.class);
    ImageService svc = new ImageService(files, images, mock(UserRepository.class), guard);
    when(files.exists("missing.png")).thenReturn(false);
    when(files.exists("../../etc/passwd.png")).thenThrow(new IllegalArgumentException("path"));

    assertError(() -> svc.open("index.html"), ErrorCode.NOT_FOUND, null);
    assertError(() -> svc.open("missing.png"), ErrorCode.NOT_FOUND, null);
    assertError(() -> svc.open("../../etc/passwd.png"), ErrorCode.NOT_FOUND, null);
    verify(files, never()).exists("index.html");
    verify(files, never()).open(anyString());
  }

  @Test
  @DisplayName("있는 파일은 그대로 열고, 저장소 입출력 오류는 NOT_FOUND로 숨기지 않는다")
  void openReturnsStreamAndPropagatesIoErrors() throws IOException {
    String name = storage.store(new ByteArrayInputStream(TestImages.png()), "png");
    try (InputStream in = service.open(name)) {
      assertThat(in.readAllBytes()).isEqualTo(TestImages.png());
    }

    FileStorage files = mock(FileStorage.class);
    when(files.exists("a.png")).thenReturn(true);
    when(files.open("a.png")).thenThrow(new IOException("disk"));
    ImageService svc = new ImageService(files, images, mock(UserRepository.class), guard);
    assertThatThrownBy(() -> svc.open("a.png")).isInstanceOf(UncheckedIOException.class);
  }

  @Test
  @DisplayName("지우기는 실패해도 요청을 막지 않는다 (남은 파일은 정리 배치가 지운다)")
  void deleteQuietlySwallowsStorageErrors() throws IOException {
    FileStorage files = mock(FileStorage.class);
    doThrow(new IOException("disk")).when(files).delete("a.png");
    doThrow(new IllegalArgumentException("bad")).when(files).delete("../x");
    ImageService svc = new ImageService(files, images, mock(UserRepository.class), guard);

    svc.deleteQuietly("a.png");
    svc.deleteQuietly("../x");
    svc.deleteQuietly(null);

    verify(files, never()).delete(null);
  }

  private void assertNotAllowed(MockMultipartFile file) {
    assertError(
        () -> service.storeCover(file),
        ErrorCode.INVALID_INPUT,
        "jpg, jpeg, png, gif, webp 이미지만 올릴 수 있어요.");
    assertThat(storage.files).isEmpty();
  }

  private static void assertError(ThrowingCallable call, ErrorCode code, String message) {
    Throwable thrown = catchThrowable(call);
    assertThat(thrown).isInstanceOf(BusinessException.class);
    assertThat(((BusinessException) thrown).getErrorCode()).isEqualTo(code);
    if (message != null) {
      assertThat(thrown).hasMessage(message);
    }
  }

  private static MockMultipartFile file(String name, String contentType, byte[] data) {
    return new MockMultipartFile("file", name, contentType, data);
  }

  /** 저장한 내용을 들여다볼 수 있는 메모리 저장소. */
  private static final class MemoryStorage implements FileStorage {
    final Map<String, byte[]> files = new HashMap<>();

    @Override
    public String store(InputStream content, String extension) throws IOException {
      String name = UUID.randomUUID() + "." + extension;
      files.put(name, content.readAllBytes());
      return name;
    }

    @Override
    public InputStream open(String storedName) throws IOException {
      return new ByteArrayInputStream(files.get(storedName));
    }

    @Override
    public boolean exists(String storedName) {
      return files.containsKey(storedName);
    }

    @Override
    public void delete(String storedName) {
      files.remove(storedName);
    }
  }
}
