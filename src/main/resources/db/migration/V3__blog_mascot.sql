-- T148: 블로그 마스코트 (BLG-14, D-116, D-118). blogJJ/blog-docs erd_tables.sql의 blogs.mascot과 같다.
ALTER TABLE blogs
  ADD COLUMN mascot VARCHAR(40) NULL
    COMMENT '마스코트 픽셀 그림 조합 바탕.색.모자.장식 (예: bear.mint.beret.heart). 바탕은 동물 10종·물건 8종. 다른 블로그와 겹치지 않게 만든다. NULL이면 아직 안 만듦 (D-116, D-118)'
    AFTER cover_image;
