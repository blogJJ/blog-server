-- T148: 블로그 마스코트 (BLG-14, D-116). blogJJ/blog-docs erd_tables.sql의 blogs.mascot과 같다.
ALTER TABLE blogs
  ADD COLUMN mascot VARCHAR(20) NULL
    COMMENT '마스코트 픽셀 그림 (동물 bear·cat·dog·rabbit·fox·penguin·bird·frog·hamster·owl, 물건 computer·camera·book·plane·headphones·gamepad·coffee·cake). NULL이면 아직 안 고름 (D-116)'
    AFTER cover_image;
