package com.blog.blog.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** blogs.mascot은 소문자 이름으로 저장한다. 모르는 값은 아직 안 고른 것(null)으로 읽는다. */
@Converter
public class MascotConverter implements AttributeConverter<Mascot, String> {

  @Override
  public String convertToDatabaseColumn(Mascot mascot) {
    return mascot == null ? null : mascot.key();
  }

  @Override
  public Mascot convertToEntityAttribute(String key) {
    return Mascot.fromKey(key).orElse(null);
  }
}
