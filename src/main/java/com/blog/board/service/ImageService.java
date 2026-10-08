package com.blog.board.service;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 이미지 올리기 (T062, BRD-05, SEC-08, D-75).
 *
 * <ul>
 *   <li>jpg·jpeg·png·gif·webp만. 확장자, 브라우저가 보낸 MIME, 파일 앞부분(시그니처) 세 가지가 모두 같은 종류여야 한다.
 *   <li>장당 3MB. 글 하나에 10장은 글을 저장할 때 센다.
 *   <li>EXIF 등 메타데이터를 지우고 UUID 이름으로 저장한다. 원래 이름은 DB에만 남기고 화면에는 주지 않는다.
 * </ul>
 */
@Service
public class ImageService {

  public static final String URL_PREFIX = "/api/images/";

  /** 올린 결과. url은 본문 마크다운에 그대로 넣는다. */
  public record Uploaded(Long id, String storedName, String url) {}

  private final FileStorage storage;
  private final PostImageRepository imageRepository;
  private final UserRepository userRepository;
  private final AccountGuard accountGuard;

  public ImageService(
      FileStorage storage,
      PostImageRepository imageRepository,
      UserRepository userRepository,
      AccountGuard accountGuard) {
    this.storage = storage;
    this.imageRepository = imageRepository;
    this.userRepository = userRepository;
    this.accountGuard = accountGuard;
  }

  public static String url(String storedName) {
    return storedName == null ? null : URL_PREFIX + storedName;
  }

  /** 글 이미지. 글을 저장하기 전에 올리므로 아직 어느 글에도 연결하지 않는다. */
  @Transactional
  public Uploaded uploadPostImage(Long userId, MultipartFile file) {
    accountGuard.check(userId, Activity.WRITE_POST);
    Checked checked = check(file);
    String storedName = store(checked);
    PostImage image =
        imageRepository.save(
            new PostImage(
                userRepository.getReferenceById(userId),
                storedName,
                originalName(file),
                checked.type().mimeType(),
                checked.data().length));
    return new Uploaded(image.getId(), storedName, url(storedName));
  }

  /** 블로그 대표 이미지·프로필 사진처럼 한 장만 두는 이미지. 저장 이름을 돌려준다. */
  public String storeSingle(MultipartFile file) {
    return store(check(file));
  }

  /** 지운다. 없거나 실패해도 요청은 계속한다 (남은 파일은 정리 배치가 지운다). */
  public void deleteQuietly(String storedName) {
    if (storedName == null) {
      return;
    }
    try {
      storage.delete(storedName);
    } catch (IOException | IllegalArgumentException ignored) {
      // 다음 정리 때 지운다
    }
  }

  /** 보여 줄 이미지를 연다. 이름이 이상하거나 없으면 NOT_FOUND. */
  public InputStream open(String storedName) {
    try {
      if (ImageType.byStoredName(storedName).isEmpty() || !storage.exists(storedName)) {
        throw new BusinessException(ErrorCode.NOT_FOUND);
      }
      return storage.open(storedName);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.NOT_FOUND);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private record Checked(ImageType type, byte[] data) {}

  private static Checked check(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "이미지를 골라 주세요.");
    }
    if (file.getSize() > PostImage.MAX_BYTES) {
      throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "이미지는 한 장에 3MB까지 올릴 수 있어요.");
    }
    String name = originalName(file);
    int dot = name.lastIndexOf('.');
    ImageType byExt =
        ImageType.byExtension(dot < 0 ? "" : name.substring(dot + 1))
            .orElseThrow(ImageService::notAllowed);
    String mime = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
    if (!mime.equals(byExt.mimeType()) && !(byExt == ImageType.JPEG && mime.equals("image/jpg"))) {
      throw notAllowed();
    }
    byte[] data;
    try {
      data = file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    ImageType bySig =
        ImageType.bySignature(Arrays.copyOf(data, Math.min(data.length, 16)))
            .orElseThrow(ImageService::notAllowed);
    if (bySig != byExt) {
      throw notAllowed();
    }
    try {
      return new Checked(bySig, ImageSanitizer.sanitize(data, bySig));
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.INVALID_INPUT, "이미지 파일이 손상되어 올릴 수 없어요.");
    }
  }

  private String store(Checked checked) {
    try {
      return storage.store(
          new ByteArrayInputStream(checked.data()), checked.type().storedExtension());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String originalName(MultipartFile file) {
    String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
    // 경로가 붙어 오는 브라우저가 있어 마지막 부분만 쓴다
    name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
    if (name.isBlank()) {
      name = "image";
    }
    return name.length() > 255 ? name.substring(name.length() - 255) : name;
  }

  private static BusinessException notAllowed() {
    return new BusinessException(
        ErrorCode.INVALID_INPUT, "jpg, jpeg, png, gif, webp 이미지만 올릴 수 있어요.");
  }
}
