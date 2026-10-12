package com.blog.blog.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** blogs.cover_scene은 study.night.rain.mint.4821 꼴로 저장한다. 모르는 장면은 아직 안 만든 것(null)으로 읽는다. */
@Converter
public class CoverSceneConverter implements AttributeConverter<CoverScene, String> {

  @Override
  public String convertToDatabaseColumn(CoverScene scene) {
    return scene == null ? null : scene.value();
  }

  @Override
  public CoverScene convertToEntityAttribute(String value) {
    return CoverScene.parse(value).orElse(null);
  }
}
