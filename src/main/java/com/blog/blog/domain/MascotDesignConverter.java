package com.blog.blog.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** blogs.mascot은 bear.mint.beret.heart 꼴로 저장한다. 모르는 바탕 그림은 아직 안 고른 것(null)으로 읽는다. */
@Converter
public class MascotDesignConverter implements AttributeConverter<MascotDesign, String> {

  @Override
  public String convertToDatabaseColumn(MascotDesign design) {
    return design == null ? null : design.value();
  }

  @Override
  public MascotDesign convertToEntityAttribute(String value) {
    return MascotDesign.parse(value).orElse(null);
  }
}
