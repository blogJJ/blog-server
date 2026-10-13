-- T131: 메인 피드·태그 검색 응답 시간 (SC-001, D-48, D-144). blogJJ/blog-docs docs/02-database/schema/add_indexes.sql과 같게 맞춤.
-- 글 30만 건에서 MySQL은 공개 블로그부터 읽고 그 블로그의 글을 idx_posts_blog로 모은 뒤 정렬한다.
-- 정렬·조건에 쓰는 컬럼(좋아요 수, 작성자, "탈퇴한 계정" 표시)을 인덱스에 함께 두면 글 본문이 든 큰 행을 읽지 않고 인덱스만으로 끝난다.
-- 한 ALTER에서 바꾸므로 blog_id 외래 키가 쓸 인덱스가 비는 순간이 없다.
ALTER TABLE posts
  DROP INDEX idx_posts_blog,
  ADD INDEX idx_posts_blog (blog_id, status, created_at, like_count, author_id, author_hidden);
