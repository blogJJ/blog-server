package com.blog.common.storage;

import java.io.IOException;
import java.io.InputStream;

/**
 * 업로드 파일 저장소 (T021, SCL-02, D-75). 1차는 서버 디스크({@link LocalDiskFileStorage}), 이중화 때 S3 구현을 하나 더해
 * 설정으로 바꾼다. 쓰는 쪽은 저장 이름만 DB에 남기고 실제 위치는 몰라도 된다.
 *
 * <p>저장 이름은 UUID라 원래 파일 이름이 드러나지 않고 겹치지 않는다. 확장자·크기·EXIF 검사는 업로드 기능(BRD-05)에서 저장 전에 한다.
 */
public interface FileStorage {

  /**
   * 파일을 저장하고 저장 이름({@code UUID.확장자})을 돌려준다.
   *
   * @param content 파일 내용. 닫는 것은 부른 쪽 책임
   * @param extension 확장자(점 없이, 예: {@code png}). 영문 소문자·숫자 1~5자
   */
  String store(InputStream content, String extension) throws IOException;

  /** 저장된 파일을 연다. 없으면 {@link java.nio.file.NoSuchFileException}. 다 읽으면 닫는다. */
  InputStream open(String storedName) throws IOException;

  boolean exists(String storedName);

  /** 지운다. 이미 없으면 아무것도 하지 않는다. */
  void delete(String storedName) throws IOException;
}
