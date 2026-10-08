package com.blog.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 디스크 저장소 (T021, SCL-02). */
class LocalDiskFileStorageTest {

  @TempDir Path dir;
  LocalDiskFileStorage storage;

  @BeforeEach
  void setUp() {
    storage = new LocalDiskFileStorage(dir.toString());
  }

  @Test
  void storesUnderUuidNameAndReadsBack() throws Exception {
    String name = storage.store(bytes("hello"), "PNG");

    assertThat(name).matches("[0-9a-f-]{36}\\.png");
    assertThat(storage.exists(name)).isTrue();
    try (InputStream in = storage.open(name)) {
      assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
    }
    // 임시 파일이 남지 않는다
    try (var files = Files.list(dir)) {
      assertThat(files).hasSize(1);
    }
  }

  @Test
  void sameContentGetsDifferentNames() throws Exception {
    assertThat(storage.store(bytes("a"), "jpg")).isNotEqualTo(storage.store(bytes("a"), "jpg"));
  }

  @Test
  void deleteRemovesAndIgnoresMissing() throws Exception {
    String name = storage.store(bytes("x"), "gif");

    storage.delete(name);
    storage.delete(name);

    assertThat(storage.exists(name)).isFalse();
    assertThatThrownBy(() -> storage.open(name)).isInstanceOf(NoSuchFileException.class);
  }

  @Test
  void rejectsPathsOutsideStorage() throws Exception {
    Files.writeString(dir.getParent().resolve("secret.txt"), "secret");

    assertThatThrownBy(() -> storage.open("../secret.txt"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> storage.delete("/etc/passwd"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> storage.store(bytes("x"), "../sh"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> storage.store(bytes("x"), "toolong"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static InputStream bytes(String s) {
    return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
  }
}
