-- T166: 새 댓글 자동 검사와 블로그장 확인 (D-133). blogJJ/blog-docs erd_tables.sql의 comments.review_status, review_reason, held_at과 같게 맞춘다.
-- 블루그린 배포 중 옛 버전도 같은 DB를 쓰므로 NULL 가능한 컬럼과 인덱스 추가만 한다. 옛 버전은 새 컬럼을 모르고,
-- 확인을 기다리는 댓글(status HIDDEN)을 관리자가 숨긴 댓글처럼 모두에게서 뺀다.

ALTER TABLE comments
  ADD COLUMN review_status ENUM('PENDING','APPROVED') NULL COMMENT '자동 검사 결과. PENDING은 숨기고 블로그장 확인 대기(status HIDDEN, 쓴 사람에게는 보임), APPROVED는 승인. 검사 안 했거나 문제없으면 NULL (D-133)',
  ADD COLUMN review_reason ENUM('SPAM','AD','ABUSE','PERSONAL_INFO','OTHER') NULL COMMENT '자동 검사가 숨긴 이유 (D-133)',
  ADD COLUMN held_at DATETIME NULL COMMENT '자동 검사가 숨긴 시각 (D-133)';

-- 블로그 관리의 댓글 확인 목록 (D-133)
CREATE INDEX idx_comments_review ON comments (review_status, held_at);
