package com.blog.common.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 서버 디스크에 파일을 둔다 (T021, SCL-02). 위치는 {@code app.storage.local-dir}(환경변수 STORAGE_LOCAL_DIR, 기본
 * ./uploads).
 *
 * <p>저장 이름 형식({@code UUID.확장자})이 아니면 열거나 지우지 않는다. {@code ../} 같은 경로로 저장 폴더 밖 파일에 닿지 못하게 하려는 것이다.
 */
@Component
public class LocalDiskFileStorage implements FileStorage {

  private static final Pattern EXTENSION = Pattern.compile("[a-z0-9]{1,5}");
  private static final Pattern STORED_NAME =
      Pattern.compile(
          "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[a-z0-9]{1,5}");

  private final Path root;

  public LocalDiskFileStorage(@Value("${app.storage.local-dir:./uploads}") String localDir) {
    this.root = Path.of(localDir).toAbsolutePath().normalize();
    try {
      Files.createDirectories(root);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot create storage directory", e);
    }
  }

  @Override
  public String store(InputStream content, String extension) throws IOException {
    String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
    if (!EXTENSION.matcher(ext).matches()) {
      throw new IllegalArgumentException("Invalid file extension");
    }
    String storedName = UUID.randomUUID() + "." + ext;
    // 다 쓴 뒤에 이름을 바꿔, 쓰는 중인 파일을 다른 요청이 읽지 않게 한다
    Path temp = Files.createTempFile(root, ".upload-", ".tmp");
    try {
      Files.copy(content, temp, StandardCopyOption.REPLACE_EXISTING);
      Files.move(temp, root.resolve(storedName), StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(temp);
    }
    return storedName;
  }

  @Override
  public InputStream open(String storedName) throws IOException {
    return Files.newInputStream(resolve(storedName));
  }

  @Override
  public boolean exists(String storedName) {
    return Files.isRegularFile(resolve(storedName));
  }

  @Override
  public void delete(String storedName) throws IOException {
    Files.deleteIfExists(resolve(storedName));
  }

  private Path resolve(String storedName) {
    if (storedName == null || !STORED_NAME.matcher(storedName).matches()) {
      throw new IllegalArgumentException("Invalid stored file name");
    }
    return root.resolve(storedName);
  }
}
