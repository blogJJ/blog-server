-- T148, T151: 블로그 마스코트와 기본 대표 픽셀 장면 (BLG-14, BLG-15, D-116, D-118, D-119). blogJJ/blog-docs erd_tables.sql의 blogs.mascot, blogs.cover_scene과 같다.
ALTER TABLE blogs
  ADD COLUMN mascot VARCHAR(40) NULL
    COMMENT '마스코트 픽셀 그림 조합 바탕.색.모자.장식 (예: bear.mint.beret.heart). 바탕은 동물 10종·물건 8종. 다른 블로그와 겹치지 않게 만든다. NULL이면 아직 안 만듦 (D-116, D-118)'
    AFTER cover_image,
  ADD COLUMN cover_scene VARCHAR(60) NULL
    COMMENT '대표 이미지가 없을 때 보여 줄 픽셀 장면 장면.시간.날씨.색.배치번호 (예: study.night.rain.mint.4821). 배치 번호는 만들 때마다 새로 뽑는다. NULL이면 아직 안 만듦 (D-119)'
    AFTER mascot;
