-- 블로그 플랫폼 더미 데이터 (회원 20명, 블로그 8개, 글·댓글·좋아요·카테고리·태그)
-- 서버가 자동으로 넣지 않습니다. 필요할 때 직접 실행하세요.
--
--   IntelliJ: Database 창에서 blog DB 콘솔을 열고 이 파일 내용을 붙여 넣어 전체 실행
--   터미널:   docker exec -i blog-mysql mysql -ublog -pblog blog < dummy/dummy_data.sql
--
-- 여러 번 실행해도 같은 데이터가 두 번 들어가지 않습니다(이미 있으면 건너뜀).
-- 회원 비밀번호는 모두 test1234! 입니다(탈퇴한 회원 '다은' 제외). admin@example.com은 메인 관리자입니다.
-- 블로그의 대표 이미지와 글 속 이미지는 넣지 않습니다(이미지 파일이 서버 폴더에 있어야 해서).

SET NAMES utf8mb4;
START TRANSACTION;

-- 1. 회원
INSERT IGNORE INTO users
  (email, password_hash, name, nickname, phone, bio, role, status, suspended_until, login_fail_count,
   notification_keep_days, terms_agreed_at, privacy_agreed_at, withdrawn_at)
VALUES
  ('admin@example.com', '$2a$10$P0o/rCVMYrGtButZsGDwy.4LtZ3.FCYts9JsZZ4MP.GkyAjH839Rm', '관리자', 'admin', '01010372053', '메인 관리자 계정', 'ADMIN', 'ACTIVE', NULL, 0, 30, NOW(), NOW(), NULL),
  ('user02@example.com', '$2a$10$P0o/rCVMYrGtButZsGDwy.4LtZ3.FCYts9JsZZ4MP.GkyAjH839Rm', '김민준', '민준의식탁', '01010742106', '민준의식탁의 블로그를 운영합니다.', 'USER', 'ACTIVE', NULL, 0, 30, NOW(), NOW(), NULL),
  ('user03@example.com', '$2a$10$P0o/rCVMYrGtButZsGDwy.4LtZ3.FCYts9JsZZ4MP.GkyAjH839Rm', '이서연', '여행하는서연', '01011112159', '여행하는서연의 블로그를 운영합니다.', 'USER', 'ACTIVE', NULL, 0, 30, NOW(), NOW(), NULL),
  ('user04@example.com', '$2a$10$P0o/rCVMYrGtButZsGDwy.4LtZ3.FCYts9JsZZ4MP.GkyAjH839Rm', '박도윤', '도윤개발', '01011482212', '도윤개발의 블로그를 운영합니다.', 'USER', 'ACTIVE', NULL, 0, 30, NOW(), NOW(), NULL),
  ('user05@example.com', '$2a$10$P0o/rCVMYrGtButZsGDwy.4LtZ3.FCYts9JsZZ4MP.GkyAjH839Rm', '최지우', '지우일기', '01011852265', NULL, 'USER', 'ACTIVE', NULL, 0, 7, NOW(), NOW(), NULL),
  ('user06@example.com', '$2a$10$P0o/rCVMYrGtButZsGDwy.4LtZ3.FCYts9JsZZ4MP.GkyAjH839Rm', '정하준', '찰칵하준', '01012222318', '찰칵하준의 블로그를 운영합니다.', 'USER', 'ACTIVE', NULL, 0, 30, NOW(), NOW(), NULL),
  ('user07@example.com', '$2a$10$P0o/rCVMYrGtButZsGDwy.4LtZ3.FCYts9JsZZ4MP.GkyAjH839Rm', '강서윤', '서윤', '01012592371', NULL, 'USER', 'ACTIVE', NULL, 0, 30, NOW(), NOW(), NULL),
  ('user08@example.com', '$2a$10$P0o/rCVMYrGtButZsGDwy.4LtZ3.FCYts9JsZZ4MP.GkyAjH839Rm', '조예준', '예준이', '01012962424', NULL, 'USER', 'ACTIVE', NULL, 0, 30, NOW(), NOW(), NULL),
  ('user09@example.com', '$2a$10$P0o/rCVMYrGtButZsGDwy.4LtZ3.FCYts9JsZZ4MP.GkyAjH839Rm', '윤지호', '지호코딩', '01013332477', NULL, 'USER', 'ACTIVE', NULL, 0, 30, NOW(), NOW(), NULL),
  ('user10@example.com', '$2a$10$P0o/rCVMYrGtButZsGDwy.4LtZ3.FCYts9JsZZ4MP.GkyAjH839Rm', '장수아', '수아요리', '01013702530', NULL, 'USER', 'ACTIVE', NULL, 0, 30, NOW(), NOW(), NULL),
  ('user11@example.com', '$2a$10$P0o/rCVMYrGtButZsGDwy.4LtZ3.FCYts9JsZZ4MP.GkyAjH839Rm', '임시우', '시우여행', '01014072583', NULL, 'USER', 'ACTIVE', NULL, 0, 30, NOW(), NOW(), NULL),
  ('user12@example.com', '$2a$10$P0o/rCVMYrGtButZsGDwy.4LtZ3.FCYts9JsZZ4MP.GkyAjH839Rm', '한지안', '지안', '01014442636', NULL, 'USER', 'ACTIVE', NULL, 0, 30, NOW(), NOW(), NULL),
  ('user13@example.com', '$2a$10$P0o/rCVMYrGtButZsGDwy.4LtZ3.FCYts9JsZZ4MP.GkyAjH839Rm', '오주원', '주원사진', '01014812689', NULL, 'USER', 'ACTIVE', NULL, 0, 30, NOW(), NOW(), NULL),
  ('user14@example.com', '$2a$10$P0o/rCVMYrGtButZsGDwy.4LtZ3.FCYts9JsZZ4MP.GkyAjH839Rm', '서하은', '하은', '01015182742', NULL, 'USER', 'ACTIVE', NULL, 0, 30, NOW(), NOW(), NULL),
  ('user15@example.com', '$2a$10$P0o/rCVMYrGtButZsGDwy.4LtZ3.FCYts9JsZZ4MP.GkyAjH839Rm', '신건우', '건우', '01015552795', NULL, 'USER', 'ACTIVE', NULL, 0, 30, NOW(), NOW(), NULL),
  ('user16@example.com', '$2a$10$P0o/rCVMYrGtButZsGDwy.4LtZ3.FCYts9JsZZ4MP.GkyAjH839Rm', '권유나', '유나맛집', '01015922848', NULL, 'USER', 'ACTIVE', NULL, 0, 30, NOW(), NOW(), NULL),
  ('user17@example.com', '$2a$10$P0o/rCVMYrGtButZsGDwy.4LtZ3.FCYts9JsZZ4MP.GkyAjH839Rm', '황준서', '준서', '01016292901', NULL, 'USER', 'ACTIVE', NULL, 3, 30, NOW(), NOW(), NULL),
  ('user18@example.com', '$2a$10$P0o/rCVMYrGtButZsGDwy.4LtZ3.FCYts9JsZZ4MP.GkyAjH839Rm', '안채원', '채원', '01016662954', NULL, 'USER', 'ACTIVE', NULL, 0, 30, NOW(), NOW(), NULL),
  ('user19@example.com', '$2a$10$P0o/rCVMYrGtButZsGDwy.4LtZ3.FCYts9JsZZ4MP.GkyAjH839Rm', '송현우', '현우', '01017033007', NULL, 'USER', 'ACTIVE', '2026-10-18 12:00:00', 0, 30, NOW(), NOW(), NULL),
  (NULL, '$2a$10$withdrawnWithdrawnWithdrOe0vZ1n3m8o2kQ3vXo7WcKp9yQ4zZ6a', '류다은', '다은', '01017403060', NULL, 'USER', 'WITHDRAWN', NULL, 0, 30, NOW(), NOW(), '2026-10-02 21:00:00');

-- 2. 블로그 (블로그장도 blog_members에 OWNER로 들어간다)
INSERT IGNORE INTO blogs (owner_id, slug, name, description, visibility, share_key, join_policy, created_at)
  SELECT (SELECT id FROM users WHERE email = 'user02@example.com'), 'minjun-table', '민준의 식탁', '집에서 해 먹는 한 끼를 기록해요.', 'PUBLIC', NULL, 'OPEN', NOW() - INTERVAL 30 DAY;
INSERT IGNORE INTO blogs (owner_id, slug, name, description, visibility, share_key, join_policy, created_at)
  SELECT (SELECT id FROM users WHERE email = 'user03@example.com'), 'seoyeon-travel', '서연의 여행 노트', '국내외 여행 기록과 꿀팁', 'PUBLIC', NULL, 'OPEN', NOW() - INTERVAL 29 DAY;
INSERT IGNORE INTO blogs (owner_id, slug, name, description, visibility, share_key, join_policy, created_at)
  SELECT (SELECT id FROM users WHERE email = 'user04@example.com'), 'doyun-dev', '도윤 개발 블로그', 'Java, Spring 공부와 회고', 'PUBLIC', NULL, 'APPROVAL', NOW() - INTERVAL 28 DAY;
INSERT IGNORE INTO blogs (owner_id, slug, name, description, visibility, share_key, join_policy, created_at)
  SELECT (SELECT id FROM users WHERE email = 'user05@example.com'), 'jiwoo-diary', '지우 일기장', '나만 보는 하루 기록', 'PRIVATE', NULL, 'APPROVAL', NOW() - INTERVAL 27 DAY;
INSERT IGNORE INTO blogs (owner_id, slug, name, description, visibility, share_key, join_policy, created_at)
  SELECT (SELECT id FROM users WHERE email = 'user06@example.com'), 'hajun-photo', '찰칵 하준', '필름 카메라로 담은 풍경', 'PUBLIC', NULL, 'OPEN', NOW() - INTERVAL 26 DAY;
INSERT IGNORE INTO blogs (owner_id, slug, name, description, visibility, share_key, join_policy, created_at)
  SELECT (SELECT id FROM users WHERE email = 'user09@example.com'), 'jiho-algo', '지호의 알고리즘 스터디', '매주 함께 문제 풀어요', 'PUBLIC', NULL, 'APPROVAL', NOW() - INTERVAL 25 DAY;
INSERT IGNORE INTO blogs (owner_id, slug, name, description, visibility, share_key, join_policy, created_at)
  SELECT (SELECT id FROM users WHERE email = 'user10@example.com'), 'sua-kitchen', '수아의 비밀 레시피', '링크를 받은 사람만 볼 수 있어요', 'LINK_ONLY', 'dummy-share-sua-kitchen', 'OPEN', NOW() - INTERVAL 24 DAY;
INSERT IGNORE INTO blogs (owner_id, slug, name, description, visibility, share_key, join_policy, created_at)
  SELECT (SELECT id FROM users WHERE email = 'user16@example.com'), 'yuna-eats', '유나의 맛집 지도', '발로 뛰어 찾은 맛집', 'PUBLIC', NULL, 'OPEN', NOW() - INTERVAL 23 DAY;

-- 3. 멤버·부블로그장 권한
INSERT IGNORE INTO blog_members (blog_id, user_id, role, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'minjun-table'), (SELECT id FROM users WHERE email = 'user02@example.com'), 'OWNER', NOW() - INTERVAL 30 DAY;
INSERT IGNORE INTO blog_members (blog_id, user_id, role, manager_since, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'minjun-table'), (SELECT id FROM users WHERE email = 'user03@example.com'), 'MEMBER', NULL, NOW() - INTERVAL 25 DAY;
INSERT IGNORE INTO blog_members (blog_id, user_id, role, manager_since, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'minjun-table'), (SELECT id FROM users WHERE email = 'user10@example.com'), 'MANAGER', NOW() - INTERVAL 19 DAY, NOW() - INTERVAL 24 DAY;
INSERT IGNORE INTO blog_members (blog_id, user_id, role, manager_since, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'minjun-table'), (SELECT id FROM users WHERE email = 'user16@example.com'), 'MEMBER', NULL, NOW() - INTERVAL 23 DAY;
INSERT IGNORE INTO blog_members (blog_id, user_id, role, manager_since, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'minjun-table'), (SELECT id FROM users WHERE email = 'user08@example.com'), 'MEMBER', NULL, NOW() - INTERVAL 22 DAY;
INSERT IGNORE INTO blog_members (blog_id, user_id, role, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'seoyeon-travel'), (SELECT id FROM users WHERE email = 'user03@example.com'), 'OWNER', NOW() - INTERVAL 29 DAY;
INSERT IGNORE INTO blog_members (blog_id, user_id, role, manager_since, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'seoyeon-travel'), (SELECT id FROM users WHERE email = 'user06@example.com'), 'MEMBER', NULL, NOW() - INTERVAL 25 DAY;
INSERT IGNORE INTO blog_members (blog_id, user_id, role, manager_since, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'seoyeon-travel'), (SELECT id FROM users WHERE email = 'user11@example.com'), 'MANAGER', NOW() - INTERVAL 19 DAY, NOW() - INTERVAL 24 DAY;
INSERT IGNORE INTO blog_members (blog_id, user_id, role, manager_since, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'seoyeon-travel'), (SELECT id FROM users WHERE email = 'user13@example.com'), 'MEMBER', NULL, NOW() - INTERVAL 23 DAY;
INSERT IGNORE INTO blog_members (blog_id, user_id, role, manager_since, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'seoyeon-travel'), (SELECT id FROM users WHERE email = 'user02@example.com'), 'MEMBER', NULL, NOW() - INTERVAL 22 DAY;
INSERT IGNORE INTO blog_members (blog_id, user_id, role, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'doyun-dev'), (SELECT id FROM users WHERE email = 'user04@example.com'), 'OWNER', NOW() - INTERVAL 28 DAY;
INSERT IGNORE INTO blog_members (blog_id, user_id, role, manager_since, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'doyun-dev'), (SELECT id FROM users WHERE email = 'user09@example.com'), 'MANAGER', NOW() - INTERVAL 20 DAY, NOW() - INTERVAL 25 DAY;
INSERT IGNORE INTO blog_members (blog_id, user_id, role, manager_since, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'doyun-dev'), (SELECT id FROM users WHERE email = 'user15@example.com'), 'MEMBER', NULL, NOW() - INTERVAL 24 DAY;
INSERT IGNORE INTO blog_members (blog_id, user_id, role, manager_since, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'doyun-dev'), (SELECT id FROM users WHERE email = 'user12@example.com'), 'MEMBER', NULL, NOW() - INTERVAL 23 DAY;
INSERT IGNORE INTO blog_members (blog_id, user_id, role, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'jiwoo-diary'), (SELECT id FROM users WHERE email = 'user05@example.com'), 'OWNER', NOW() - INTERVAL 27 DAY;
INSERT IGNORE INTO blog_members (blog_id, user_id, role, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'hajun-photo'), (SELECT id FROM users WHERE email = 'user06@example.com'), 'OWNER', NOW() - INTERVAL 26 DAY;
INSERT IGNORE INTO blog_members (blog_id, user_id, role, manager_since, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'hajun-photo'), (SELECT id FROM users WHERE email = 'user13@example.com'), 'MANAGER', NOW() - INTERVAL 20 DAY, NOW() - INTERVAL 25 DAY;
INSERT IGNORE INTO blog_members (blog_id, user_id, role, manager_since, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'hajun-photo'), (SELECT id FROM users WHERE email = 'user03@example.com'), 'MEMBER', NULL, NOW() - INTERVAL 24 DAY;
INSERT IGNORE INTO blog_members (blog_id, user_id, role, manager_since, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'hajun-photo'), (SELECT id FROM users WHERE email = 'user07@example.com'), 'MEMBER', NULL, NOW() - INTERVAL 23 DAY;
INSERT IGNORE INTO blog_members (blog_id, user_id, role, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'jiho-algo'), (SELECT id FROM users WHERE email = 'user09@example.com'), 'OWNER', NOW() - INTERVAL 25 DAY;
INSERT IGNORE INTO blog_members (blog_id, user_id, role, manager_since, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'jiho-algo'), (SELECT id FROM users WHERE email = 'user04@example.com'), 'MEMBER', NULL, NOW() - INTERVAL 25 DAY;
INSERT IGNORE INTO blog_members (blog_id, user_id, role, manager_since, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'jiho-algo'), (SELECT id FROM users WHERE email = 'user15@example.com'), 'MEMBER', NULL, NOW() - INTERVAL 24 DAY;
INSERT IGNORE INTO blog_members (blog_id, user_id, role, manager_since, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'jiho-algo'), (SELECT id FROM users WHERE email = 'user14@example.com'), 'MEMBER', NULL, NOW() - INTERVAL 23 DAY;
INSERT IGNORE INTO blog_members (blog_id, user_id, role, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'sua-kitchen'), (SELECT id FROM users WHERE email = 'user10@example.com'), 'OWNER', NOW() - INTERVAL 24 DAY;
INSERT IGNORE INTO blog_members (blog_id, user_id, role, manager_since, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'sua-kitchen'), (SELECT id FROM users WHERE email = 'user02@example.com'), 'MEMBER', NULL, NOW() - INTERVAL 25 DAY;
INSERT IGNORE INTO blog_members (blog_id, user_id, role, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'yuna-eats'), (SELECT id FROM users WHERE email = 'user16@example.com'), 'OWNER', NOW() - INTERVAL 23 DAY;
INSERT IGNORE INTO blog_members (blog_id, user_id, role, manager_since, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'yuna-eats'), (SELECT id FROM users WHERE email = 'user02@example.com'), 'MEMBER', NULL, NOW() - INTERVAL 25 DAY;
INSERT IGNORE INTO blog_members (blog_id, user_id, role, manager_since, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'yuna-eats'), (SELECT id FROM users WHERE email = 'user18@example.com'), 'MEMBER', NULL, NOW() - INTERVAL 24 DAY;
INSERT IGNORE INTO blog_members (blog_id, user_id, role, manager_since, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'yuna-eats'), (SELECT id FROM users WHERE email = 'user17@example.com'), 'MEMBER', NULL, NOW() - INTERVAL 23 DAY;
INSERT IGNORE INTO blog_members (blog_id, user_id, role, manager_since, joined_at) SELECT (SELECT id FROM blogs WHERE slug = 'yuna-eats'), (SELECT id FROM users WHERE email = 'user08@example.com'), 'MEMBER', NULL, NOW() - INTERVAL 22 DAY;
INSERT IGNORE INTO blog_manager_permissions (blog_member_id, permission)
  SELECT m.id, 'EDIT_INFO' FROM blog_members m WHERE m.blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND m.user_id = (SELECT id FROM users WHERE email = 'user10@example.com');
INSERT IGNORE INTO blog_manager_permissions (blog_member_id, permission)
  SELECT m.id, 'MANAGE_POSTS' FROM blog_members m WHERE m.blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND m.user_id = (SELECT id FROM users WHERE email = 'user10@example.com');
INSERT IGNORE INTO blog_manager_permissions (blog_member_id, permission)
  SELECT m.id, 'MANAGE_MEMBERS' FROM blog_members m WHERE m.blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel') AND m.user_id = (SELECT id FROM users WHERE email = 'user11@example.com');
INSERT IGNORE INTO blog_manager_permissions (blog_member_id, permission)
  SELECT m.id, 'MANAGE_MEMBERS' FROM blog_members m WHERE m.blog_id = (SELECT id FROM blogs WHERE slug = 'doyun-dev') AND m.user_id = (SELECT id FROM users WHERE email = 'user09@example.com');
INSERT IGNORE INTO blog_manager_permissions (blog_member_id, permission)
  SELECT m.id, 'MANAGE_POSTS' FROM blog_members m WHERE m.blog_id = (SELECT id FROM blogs WHERE slug = 'doyun-dev') AND m.user_id = (SELECT id FROM users WHERE email = 'user09@example.com');
INSERT IGNORE INTO blog_manager_permissions (blog_member_id, permission)
  SELECT m.id, 'EDIT_INFO' FROM blog_members m WHERE m.blog_id = (SELECT id FROM blogs WHERE slug = 'hajun-photo') AND m.user_id = (SELECT id FROM users WHERE email = 'user13@example.com');

-- 4. 카테고리·태그
INSERT IGNORE INTO categories (blog_id, name, sort_order) SELECT (SELECT id FROM blogs WHERE slug = 'minjun-table'), '한식', 0;
INSERT IGNORE INTO categories (blog_id, name, sort_order) SELECT (SELECT id FROM blogs WHERE slug = 'minjun-table'), '양식', 1;
INSERT IGNORE INTO categories (blog_id, name, sort_order) SELECT (SELECT id FROM blogs WHERE slug = 'minjun-table'), '디저트', 2;
INSERT IGNORE INTO categories (blog_id, name, sort_order) SELECT (SELECT id FROM blogs WHERE slug = 'seoyeon-travel'), '국내', 0;
INSERT IGNORE INTO categories (blog_id, name, sort_order) SELECT (SELECT id FROM blogs WHERE slug = 'seoyeon-travel'), '해외', 1;
INSERT IGNORE INTO categories (blog_id, name, sort_order) SELECT (SELECT id FROM blogs WHERE slug = 'seoyeon-travel'), '준비물', 2;
INSERT IGNORE INTO categories (blog_id, name, sort_order) SELECT (SELECT id FROM blogs WHERE slug = 'doyun-dev'), 'Java', 0;
INSERT IGNORE INTO categories (blog_id, name, sort_order) SELECT (SELECT id FROM blogs WHERE slug = 'doyun-dev'), 'Spring', 1;
INSERT IGNORE INTO categories (blog_id, name, sort_order) SELECT (SELECT id FROM blogs WHERE slug = 'doyun-dev'), '회고', 2;
INSERT IGNORE INTO categories (blog_id, name, sort_order) SELECT (SELECT id FROM blogs WHERE slug = 'jiwoo-diary'), '일상', 0;
INSERT IGNORE INTO categories (blog_id, name, sort_order) SELECT (SELECT id FROM blogs WHERE slug = 'hajun-photo'), '풍경', 0;
INSERT IGNORE INTO categories (blog_id, name, sort_order) SELECT (SELECT id FROM blogs WHERE slug = 'hajun-photo'), '인물', 1;
INSERT IGNORE INTO categories (blog_id, name, sort_order) SELECT (SELECT id FROM blogs WHERE slug = 'hajun-photo'), '장비', 2;
INSERT IGNORE INTO categories (blog_id, name, sort_order) SELECT (SELECT id FROM blogs WHERE slug = 'jiho-algo'), '알고리즘', 0;
INSERT IGNORE INTO categories (blog_id, name, sort_order) SELECT (SELECT id FROM blogs WHERE slug = 'jiho-algo'), '스터디 공지', 1;
INSERT IGNORE INTO categories (blog_id, name, sort_order) SELECT (SELECT id FROM blogs WHERE slug = 'sua-kitchen'), '베이킹', 0;
INSERT IGNORE INTO categories (blog_id, name, sort_order) SELECT (SELECT id FROM blogs WHERE slug = 'yuna-eats'), '서울', 0;
INSERT IGNORE INTO categories (blog_id, name, sort_order) SELECT (SELECT id FROM blogs WHERE slug = 'yuna-eats'), '부산', 1;
INSERT IGNORE INTO categories (blog_id, name, sort_order) SELECT (SELECT id FROM blogs WHERE slug = 'yuna-eats'), '카페', 2;
INSERT IGNORE INTO tags (name) VALUES ('java');
INSERT IGNORE INTO tags (name) VALUES ('spring');
INSERT IGNORE INTO tags (name) VALUES ('개발');
INSERT IGNORE INTO tags (name) VALUES ('레시피');
INSERT IGNORE INTO tags (name) VALUES ('맛집');
INSERT IGNORE INTO tags (name) VALUES ('베이킹');
INSERT IGNORE INTO tags (name) VALUES ('사진');
INSERT IGNORE INTO tags (name) VALUES ('알고리즘');
INSERT IGNORE INTO tags (name) VALUES ('여행');
INSERT IGNORE INTO tags (name) VALUES ('요리');
INSERT IGNORE INTO tags (name) VALUES ('일기');
INSERT IGNORE INTO tags (name) VALUES ('카페');
INSERT IGNORE INTO tags (name) VALUES ('코딩테스트');
INSERT IGNORE INTO tags (name) VALUES ('필름');
INSERT IGNORE INTO blog_tags (blog_id, tag_id) SELECT (SELECT id FROM blogs WHERE slug = 'minjun-table'), (SELECT id FROM tags WHERE name = '요리');
INSERT IGNORE INTO blog_tags (blog_id, tag_id) SELECT (SELECT id FROM blogs WHERE slug = 'minjun-table'), (SELECT id FROM tags WHERE name = '레시피');
INSERT IGNORE INTO blog_tags (blog_id, tag_id) SELECT (SELECT id FROM blogs WHERE slug = 'seoyeon-travel'), (SELECT id FROM tags WHERE name = '여행');
INSERT IGNORE INTO blog_tags (blog_id, tag_id) SELECT (SELECT id FROM blogs WHERE slug = 'seoyeon-travel'), (SELECT id FROM tags WHERE name = '사진');
INSERT IGNORE INTO blog_tags (blog_id, tag_id) SELECT (SELECT id FROM blogs WHERE slug = 'doyun-dev'), (SELECT id FROM tags WHERE name = 'java');
INSERT IGNORE INTO blog_tags (blog_id, tag_id) SELECT (SELECT id FROM blogs WHERE slug = 'doyun-dev'), (SELECT id FROM tags WHERE name = 'spring');
INSERT IGNORE INTO blog_tags (blog_id, tag_id) SELECT (SELECT id FROM blogs WHERE slug = 'doyun-dev'), (SELECT id FROM tags WHERE name = '개발');
INSERT IGNORE INTO blog_tags (blog_id, tag_id) SELECT (SELECT id FROM blogs WHERE slug = 'jiwoo-diary'), (SELECT id FROM tags WHERE name = '일기');
INSERT IGNORE INTO blog_tags (blog_id, tag_id) SELECT (SELECT id FROM blogs WHERE slug = 'hajun-photo'), (SELECT id FROM tags WHERE name = '사진');
INSERT IGNORE INTO blog_tags (blog_id, tag_id) SELECT (SELECT id FROM blogs WHERE slug = 'hajun-photo'), (SELECT id FROM tags WHERE name = '필름');
INSERT IGNORE INTO blog_tags (blog_id, tag_id) SELECT (SELECT id FROM blogs WHERE slug = 'jiho-algo'), (SELECT id FROM tags WHERE name = '알고리즘');
INSERT IGNORE INTO blog_tags (blog_id, tag_id) SELECT (SELECT id FROM blogs WHERE slug = 'jiho-algo'), (SELECT id FROM tags WHERE name = '코딩테스트');
INSERT IGNORE INTO blog_tags (blog_id, tag_id) SELECT (SELECT id FROM blogs WHERE slug = 'sua-kitchen'), (SELECT id FROM tags WHERE name = '베이킹');
INSERT IGNORE INTO blog_tags (blog_id, tag_id) SELECT (SELECT id FROM blogs WHERE slug = 'yuna-eats'), (SELECT id FROM tags WHERE name = '맛집');
INSERT IGNORE INTO blog_tags (blog_id, tag_id) SELECT (SELECT id FROM blogs WHERE slug = 'yuna-eats'), (SELECT id FROM tags WHERE name = '카페');

-- 5. 글 (같은 블로그에 같은 제목이 있으면 건너뛴다)
INSERT INTO posts (blog_id, author_id, category_id, title, content, is_notice, view_count, created_at, updated_at)
  SELECT (SELECT id FROM blogs WHERE slug = 'minjun-table'), (SELECT id FROM users WHERE email = 'user02@example.com'), NULL, '블로그 이용 안내', '이 블로그는 집밥 레시피를 모읍니다.

- 레시피 글에는 **재료**와 **순서**를 꼭 적어 주세요.
- 광고 글은 지워요.', TRUE, 87, NOW() - INTERVAL 12 DAY, NOW() - INTERVAL 12 DAY
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND title = '블로그 이용 안내');
INSERT INTO posts (blog_id, author_id, category_id, title, content, is_notice, view_count, created_at, updated_at)
  SELECT (SELECT id FROM blogs WHERE slug = 'minjun-table'), (SELECT id FROM users WHERE email = 'user02@example.com'), (SELECT id FROM categories WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND name = '한식'), '된장찌개 황금 비율', '## 재료
- 된장 2큰술
- 애호박 1/3개
- 두부 반 모

## 순서
1. 멸치 육수를 낸다.
2. 된장을 풀고 채소를 넣는다.
3. 두부는 마지막에!', FALSE, 73, NOW() - INTERVAL 10 DAY, NOW() - INTERVAL 10 DAY
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND title = '된장찌개 황금 비율');
INSERT INTO posts (blog_id, author_id, category_id, title, content, is_notice, view_count, created_at, updated_at)
  SELECT (SELECT id FROM blogs WHERE slug = 'minjun-table'), (SELECT id FROM users WHERE email = 'user10@example.com'), (SELECT id FROM categories WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND name = '양식'), '15분 크림 파스타', '생크림 없이 우유로 만드는 크림 파스타예요.

버터에 마늘을 볶고, 우유와 치즈를 넣어 졸이면 끝.', FALSE, 59, NOW() - INTERVAL 8 DAY, NOW() - INTERVAL 8 DAY
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND title = '15분 크림 파스타');
INSERT INTO posts (blog_id, author_id, category_id, title, content, is_notice, view_count, created_at, updated_at)
  SELECT (SELECT id FROM blogs WHERE slug = 'minjun-table'), (SELECT id FROM users WHERE email = 'user03@example.com'), (SELECT id FROM categories WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND name = '디저트'), '에어프라이어 군고구마', '180도에서 40분.
중간에 한 번 뒤집어 주세요.', FALSE, 38, NOW() - INTERVAL 5 DAY, NOW() - INTERVAL 5 DAY
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND title = '에어프라이어 군고구마');
INSERT INTO posts (blog_id, author_id, category_id, title, content, is_notice, view_count, created_at, updated_at)
  SELECT (SELECT id FROM blogs WHERE slug = 'minjun-table'), (SELECT id FROM users WHERE email = 'user16@example.com'), (SELECT id FROM categories WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND name = '한식'), '엄마표 제육볶음', '고추장 2, 간장 1, 설탕 1, 다진 마늘 1.

센 불에 빠르게 볶는 게 포인트예요.', FALSE, 17, NOW() - INTERVAL 2 DAY, NOW() - INTERVAL 2 DAY
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND title = '엄마표 제육볶음');
INSERT INTO posts (blog_id, author_id, category_id, title, content, is_notice, view_count, created_at, updated_at)
  SELECT (SELECT id FROM blogs WHERE slug = 'seoyeon-travel'), (SELECT id FROM users WHERE email = 'user03@example.com'), (SELECT id FROM categories WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel') AND name = '국내'), '제주 3박 4일 코스', '## 1일차
협재 해변 → 한림공원

## 2일차
성산일출봉 → 우도

> 렌터카는 미리 예약하세요.', FALSE, 101, NOW() - INTERVAL 14 DAY, NOW() - INTERVAL 14 DAY
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel') AND title = '제주 3박 4일 코스');
INSERT INTO posts (blog_id, author_id, category_id, title, content, is_notice, view_count, created_at, updated_at)
  SELECT (SELECT id FROM blogs WHERE slug = 'seoyeon-travel'), (SELECT id FROM users WHERE email = 'user03@example.com'), (SELECT id FROM categories WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel') AND name = '해외'), '오사카 먹방 여행', '도톤보리 타코야키, 쿠로몬 시장 참치.

교통패스는 주유패스 추천!', FALSE, 66, NOW() - INTERVAL 9 DAY, NOW() - INTERVAL 9 DAY
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel') AND title = '오사카 먹방 여행');
INSERT INTO posts (blog_id, author_id, category_id, title, content, is_notice, view_count, created_at, updated_at)
  SELECT (SELECT id FROM blogs WHERE slug = 'seoyeon-travel'), (SELECT id FROM users WHERE email = 'user11@example.com'), (SELECT id FROM categories WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel') AND name = '준비물'), '여행 짐 싸기 체크리스트', '- 여권·신분증
- 충전기와 보조배터리
- 상비약
- 우산', FALSE, 45, NOW() - INTERVAL 6 DAY, NOW() - INTERVAL 6 DAY
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel') AND title = '여행 짐 싸기 체크리스트');
INSERT INTO posts (blog_id, author_id, category_id, title, content, is_notice, view_count, created_at, updated_at)
  SELECT (SELECT id FROM blogs WHERE slug = 'seoyeon-travel'), (SELECT id FROM users WHERE email = 'user06@example.com'), (SELECT id FROM categories WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel') AND name = '국내'), '강릉 카페 거리', '안목해변 카페 거리에서 바다 보며 커피 한 잔.', FALSE, 10, NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 1 DAY
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel') AND title = '강릉 카페 거리');
INSERT INTO posts (blog_id, author_id, category_id, title, content, is_notice, view_count, created_at, updated_at)
  SELECT (SELECT id FROM blogs WHERE slug = 'doyun-dev'), (SELECT id FROM users WHERE email = 'user04@example.com'), NULL, '스터디 규칙', '매주 화요일 글 하나씩 올리기.
질문은 댓글로 남겨 주세요.', TRUE, 143, NOW() - INTERVAL 20 DAY, NOW() - INTERVAL 20 DAY
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'doyun-dev') AND title = '스터디 규칙');
INSERT INTO posts (blog_id, author_id, category_id, title, content, is_notice, view_count, created_at, updated_at)
  SELECT (SELECT id FROM blogs WHERE slug = 'doyun-dev'), (SELECT id FROM users WHERE email = 'user04@example.com'), (SELECT id FROM categories WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'doyun-dev') AND name = 'Java'), 'record는 언제 쓸까', '`record`는 값만 담는 불변 객체에 씁니다.

```java
public record Point(int x, int y) {}
```', FALSE, 80, NOW() - INTERVAL 11 DAY, NOW() - INTERVAL 11 DAY
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'doyun-dev') AND title = 'record는 언제 쓸까');
INSERT INTO posts (blog_id, author_id, category_id, title, content, is_notice, view_count, created_at, updated_at)
  SELECT (SELECT id FROM blogs WHERE slug = 'doyun-dev'), (SELECT id FROM users WHERE email = 'user09@example.com'), (SELECT id FROM categories WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'doyun-dev') AND name = 'Spring'), '@Transactional 기본 정리', '- 기본 전파는 REQUIRED
- 체크 예외는 롤백되지 않아요
- 같은 클래스 안에서 부르면 프록시가 적용되지 않아요', FALSE, 52, NOW() - INTERVAL 7 DAY, NOW() - INTERVAL 7 DAY
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'doyun-dev') AND title = '@Transactional 기본 정리');
INSERT INTO posts (blog_id, author_id, category_id, title, content, is_notice, view_count, created_at, updated_at)
  SELECT (SELECT id FROM blogs WHERE slug = 'doyun-dev'), (SELECT id FROM users WHERE email = 'user15@example.com'), (SELECT id FROM categories WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'doyun-dev') AND name = '회고'), '첫 프로젝트 회고', '잘한 점: 테스트를 먼저 썼다.
아쉬운 점: 문서를 늦게 썼다.', FALSE, 24, NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 3 DAY
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'doyun-dev') AND title = '첫 프로젝트 회고');
INSERT INTO posts (blog_id, author_id, category_id, title, content, is_notice, view_count, created_at, updated_at)
  SELECT (SELECT id FROM blogs WHERE slug = 'jiwoo-diary'), (SELECT id FROM users WHERE email = 'user05@example.com'), (SELECT id FROM categories WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'jiwoo-diary') AND name = '일상'), '오늘의 기록', '비가 와서 집에서 책을 읽었다.', FALSE, 31, NOW() - INTERVAL 4 DAY, NOW() - INTERVAL 4 DAY
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'jiwoo-diary') AND title = '오늘의 기록');
INSERT INTO posts (blog_id, author_id, category_id, title, content, is_notice, view_count, created_at, updated_at)
  SELECT (SELECT id FROM blogs WHERE slug = 'hajun-photo'), (SELECT id FROM users WHERE email = 'user06@example.com'), (SELECT id FROM categories WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'hajun-photo') AND name = '풍경'), '새벽 한강', '해 뜨기 전 30분이 제일 예뻐요.

ISO 200, f/8, 1/30s.', FALSE, 94, NOW() - INTERVAL 13 DAY, NOW() - INTERVAL 13 DAY
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'hajun-photo') AND title = '새벽 한강');
INSERT INTO posts (blog_id, author_id, category_id, title, content, is_notice, view_count, created_at, updated_at)
  SELECT (SELECT id FROM blogs WHERE slug = 'hajun-photo'), (SELECT id FROM users WHERE email = 'user13@example.com'), (SELECT id FROM categories WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'hajun-photo') AND name = '인물'), '역광 인물 사진 팁', '해를 등지고 서서 반사판으로 얼굴을 밝혀 주세요.', FALSE, 45, NOW() - INTERVAL 6 DAY, NOW() - INTERVAL 6 DAY
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'hajun-photo') AND title = '역광 인물 사진 팁');
INSERT INTO posts (blog_id, author_id, category_id, title, content, is_notice, view_count, created_at, updated_at)
  SELECT (SELECT id FROM blogs WHERE slug = 'hajun-photo'), (SELECT id FROM users WHERE email = 'user06@example.com'), (SELECT id FROM categories WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'hajun-photo') AND name = '장비'), '입문용 필름 카메라 추천', '- 캐논 AE-1
- 니콘 FM2
- 펜탁스 K1000', FALSE, 17, NOW() - INTERVAL 2 DAY, NOW() - INTERVAL 2 DAY
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'hajun-photo') AND title = '입문용 필름 카메라 추천');
INSERT INTO posts (blog_id, author_id, category_id, title, content, is_notice, view_count, created_at, updated_at)
  SELECT (SELECT id FROM blogs WHERE slug = 'jiho-algo'), (SELECT id FROM users WHERE email = 'user09@example.com'), (SELECT id FROM categories WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'jiho-algo') AND name = '스터디 공지'), '10월 스터디 일정', '| 주차 | 주제 |
|---|---|
| 1 | 그리디 |
| 2 | DP |
| 3 | 그래프 |', TRUE, 66, NOW() - INTERVAL 9 DAY, NOW() - INTERVAL 9 DAY
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'jiho-algo') AND title = '10월 스터디 일정');
INSERT INTO posts (blog_id, author_id, category_id, title, content, is_notice, view_count, created_at, updated_at)
  SELECT (SELECT id FROM blogs WHERE slug = 'jiho-algo'), (SELECT id FROM users WHERE email = 'user09@example.com'), (SELECT id FROM categories WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'jiho-algo') AND name = '알고리즘'), 'BFS 기본 틀', '```java
Queue<Integer> q = new ArrayDeque<>();
q.add(start);
```
방문 체크는 넣을 때!', FALSE, 59, NOW() - INTERVAL 8 DAY, NOW() - INTERVAL 8 DAY
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'jiho-algo') AND title = 'BFS 기본 틀');
INSERT INTO posts (blog_id, author_id, category_id, title, content, is_notice, view_count, created_at, updated_at)
  SELECT (SELECT id FROM blogs WHERE slug = 'jiho-algo'), (SELECT id FROM users WHERE email = 'user04@example.com'), (SELECT id FROM categories WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'jiho-algo') AND name = '알고리즘'), 'DP 점화식 세우는 법', '1. 상태 정의
2. 작은 문제로 나누기
3. 기저 조건', FALSE, 31, NOW() - INTERVAL 4 DAY, NOW() - INTERVAL 4 DAY
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'jiho-algo') AND title = 'DP 점화식 세우는 법');
INSERT INTO posts (blog_id, author_id, category_id, title, content, is_notice, view_count, created_at, updated_at)
  SELECT (SELECT id FROM blogs WHERE slug = 'sua-kitchen'), (SELECT id FROM users WHERE email = 'user10@example.com'), (SELECT id FROM categories WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'sua-kitchen') AND name = '베이킹'), '초코칩 쿠키', '버터 100g, 설탕 80g, 밀가루 150g, 초코칩 한 줌.
170도에서 12분.', FALSE, 52, NOW() - INTERVAL 7 DAY, NOW() - INTERVAL 7 DAY
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'sua-kitchen') AND title = '초코칩 쿠키');
INSERT INTO posts (blog_id, author_id, category_id, title, content, is_notice, view_count, created_at, updated_at)
  SELECT (SELECT id FROM blogs WHERE slug = 'yuna-eats'), (SELECT id FROM users WHERE email = 'user16@example.com'), (SELECT id FROM categories WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'yuna-eats') AND name = '서울'), '을지로 노포 3곳', '1. 평양냉면집
2. 골뱅이 골목
3. 오래된 호프집', FALSE, 73, NOW() - INTERVAL 10 DAY, NOW() - INTERVAL 10 DAY
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'yuna-eats') AND title = '을지로 노포 3곳');
INSERT INTO posts (blog_id, author_id, category_id, title, content, is_notice, view_count, created_at, updated_at)
  SELECT (SELECT id FROM blogs WHERE slug = 'yuna-eats'), (SELECT id FROM users WHERE email = 'user16@example.com'), (SELECT id FROM categories WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'yuna-eats') AND name = '부산'), '해운대 돼지국밥', '아침 일찍 가면 줄이 짧아요.', FALSE, 52, NOW() - INTERVAL 7 DAY, NOW() - INTERVAL 7 DAY
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'yuna-eats') AND title = '해운대 돼지국밥');
INSERT INTO posts (blog_id, author_id, category_id, title, content, is_notice, view_count, created_at, updated_at)
  SELECT (SELECT id FROM blogs WHERE slug = 'yuna-eats'), (SELECT id FROM users WHERE email = 'user18@example.com'), (SELECT id FROM categories WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'yuna-eats') AND name = '카페'), '성수 디저트 카페', '까눌레가 맛있는 곳.
주말엔 웨이팅이 있어요.', FALSE, 24, NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 3 DAY
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'yuna-eats') AND title = '성수 디저트 카페');
INSERT INTO posts (blog_id, author_id, category_id, title, content, is_notice, view_count, created_at, updated_at)
  SELECT (SELECT id FROM blogs WHERE slug = 'yuna-eats'), (SELECT id FROM users WHERE email = 'user02@example.com'), (SELECT id FROM categories WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'yuna-eats') AND name = '서울'), '망원시장 먹거리', '고로케, 닭강정, 칼국수.
현금 챙기세요!', FALSE, 10, NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 1 DAY
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'yuna-eats') AND title = '망원시장 먹거리');

-- 글 태그: 각 글에 그 블로그의 첫 태그를 단다
INSERT IGNORE INTO post_tags (post_id, tag_id) SELECT p.id, (SELECT id FROM tags WHERE name = '요리') FROM posts p WHERE p.blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table');
INSERT IGNORE INTO post_tags (post_id, tag_id) SELECT p.id, (SELECT id FROM tags WHERE name = '여행') FROM posts p WHERE p.blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel');
INSERT IGNORE INTO post_tags (post_id, tag_id) SELECT p.id, (SELECT id FROM tags WHERE name = 'java') FROM posts p WHERE p.blog_id = (SELECT id FROM blogs WHERE slug = 'doyun-dev');
INSERT IGNORE INTO post_tags (post_id, tag_id) SELECT p.id, (SELECT id FROM tags WHERE name = '일기') FROM posts p WHERE p.blog_id = (SELECT id FROM blogs WHERE slug = 'jiwoo-diary');
INSERT IGNORE INTO post_tags (post_id, tag_id) SELECT p.id, (SELECT id FROM tags WHERE name = '사진') FROM posts p WHERE p.blog_id = (SELECT id FROM blogs WHERE slug = 'hajun-photo');
INSERT IGNORE INTO post_tags (post_id, tag_id) SELECT p.id, (SELECT id FROM tags WHERE name = '알고리즘') FROM posts p WHERE p.blog_id = (SELECT id FROM blogs WHERE slug = 'jiho-algo');
INSERT IGNORE INTO post_tags (post_id, tag_id) SELECT p.id, (SELECT id FROM tags WHERE name = '베이킹') FROM posts p WHERE p.blog_id = (SELECT id FROM blogs WHERE slug = 'sua-kitchen');
INSERT IGNORE INTO post_tags (post_id, tag_id) SELECT p.id, (SELECT id FROM tags WHERE name = '맛집') FROM posts p WHERE p.blog_id = (SELECT id FROM blogs WHERE slug = 'yuna-eats');

-- 6. 댓글·대댓글 (대댓글은 1단계, 답한 회원은 reply_to_user_id). 같은 글에 같은 내용이 있으면 건너뛴다
INSERT INTO comments (post_id, author_id, parent_id, reply_to_user_id, content, created_at, updated_at)
  SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND title = '된장찌개 황금 비율' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user03@example.com'), NULL, NULL, '오늘 저녁 메뉴로 정했어요!', NOW() - INTERVAL 10 DAY + INTERVAL 1 HOUR, NOW() - INTERVAL 10 DAY + INTERVAL 1 HOUR
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND title = '된장찌개 황금 비율' ORDER BY id LIMIT 1) AND content = '오늘 저녁 메뉴로 정했어요!');
INSERT INTO comments (post_id, author_id, parent_id, reply_to_user_id, content, created_at, updated_at)
  SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND title = '된장찌개 황금 비율' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user02@example.com'), (SELECT id FROM (SELECT id FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND title = '된장찌개 황금 비율' ORDER BY id LIMIT 1) AND content = '오늘 저녁 메뉴로 정했어요!' ORDER BY id LIMIT 1) x), (SELECT id FROM users WHERE email = 'user03@example.com'), '맛있게 드세요 :)', NOW() - INTERVAL 10 DAY + INTERVAL 2 HOUR, NOW() - INTERVAL 10 DAY + INTERVAL 2 HOUR
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND title = '된장찌개 황금 비율' ORDER BY id LIMIT 1) AND content = '맛있게 드세요 :)');
INSERT INTO comments (post_id, author_id, parent_id, reply_to_user_id, content, created_at, updated_at)
  SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND title = '된장찌개 황금 비율' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user16@example.com'), NULL, NULL, '두부 대신 감자도 괜찮을까요?', NOW() - INTERVAL 10 DAY + INTERVAL 3 HOUR, NOW() - INTERVAL 10 DAY + INTERVAL 3 HOUR
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND title = '된장찌개 황금 비율' ORDER BY id LIMIT 1) AND content = '두부 대신 감자도 괜찮을까요?');
INSERT INTO comments (post_id, author_id, parent_id, reply_to_user_id, content, created_at, updated_at)
  SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND title = '된장찌개 황금 비율' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user10@example.com'), (SELECT id FROM (SELECT id FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND title = '된장찌개 황금 비율' ORDER BY id LIMIT 1) AND content = '두부 대신 감자도 괜찮을까요?' ORDER BY id LIMIT 1) x), (SELECT id FROM users WHERE email = 'user16@example.com'), '감자 넣으면 더 구수해요', NOW() - INTERVAL 10 DAY + INTERVAL 4 HOUR, NOW() - INTERVAL 10 DAY + INTERVAL 4 HOUR
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND title = '된장찌개 황금 비율' ORDER BY id LIMIT 1) AND content = '감자 넣으면 더 구수해요');
INSERT INTO comments (post_id, author_id, parent_id, reply_to_user_id, content, created_at, updated_at)
  SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND title = '15분 크림 파스타' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user08@example.com'), NULL, NULL, '우유로도 되는군요', NOW() - INTERVAL 8 DAY + INTERVAL 1 HOUR, NOW() - INTERVAL 8 DAY + INTERVAL 1 HOUR
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND title = '15분 크림 파스타' ORDER BY id LIMIT 1) AND content = '우유로도 되는군요');
INSERT INTO comments (post_id, author_id, parent_id, reply_to_user_id, content, created_at, updated_at)
  SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel') AND title = '제주 3박 4일 코스' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user06@example.com'), NULL, NULL, '우도 배편은 몇 시가 좋나요?', NOW() - INTERVAL 14 DAY + INTERVAL 1 HOUR, NOW() - INTERVAL 14 DAY + INTERVAL 1 HOUR
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel') AND title = '제주 3박 4일 코스' ORDER BY id LIMIT 1) AND content = '우도 배편은 몇 시가 좋나요?');
INSERT INTO comments (post_id, author_id, parent_id, reply_to_user_id, content, created_at, updated_at)
  SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel') AND title = '제주 3박 4일 코스' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user03@example.com'), (SELECT id FROM (SELECT id FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel') AND title = '제주 3박 4일 코스' ORDER BY id LIMIT 1) AND content = '우도 배편은 몇 시가 좋나요?' ORDER BY id LIMIT 1) x), (SELECT id FROM users WHERE email = 'user06@example.com'), '오전 9시 배 추천해요', NOW() - INTERVAL 14 DAY + INTERVAL 2 HOUR, NOW() - INTERVAL 14 DAY + INTERVAL 2 HOUR
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel') AND title = '제주 3박 4일 코스' ORDER BY id LIMIT 1) AND content = '오전 9시 배 추천해요');
INSERT INTO comments (post_id, author_id, parent_id, reply_to_user_id, content, created_at, updated_at)
  SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel') AND title = '제주 3박 4일 코스' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user06@example.com'), (SELECT id FROM (SELECT id FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel') AND title = '제주 3박 4일 코스' ORDER BY id LIMIT 1) AND content = '우도 배편은 몇 시가 좋나요?' ORDER BY id LIMIT 1) x), (SELECT id FROM users WHERE email = 'user03@example.com'), '감사합니다!', NOW() - INTERVAL 14 DAY + INTERVAL 3 HOUR, NOW() - INTERVAL 14 DAY + INTERVAL 3 HOUR
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel') AND title = '제주 3박 4일 코스' ORDER BY id LIMIT 1) AND content = '감사합니다!');
INSERT INTO comments (post_id, author_id, parent_id, reply_to_user_id, content, created_at, updated_at)
  SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel') AND title = '제주 3박 4일 코스' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user13@example.com'), NULL, NULL, '사진 잘 나오는 곳도 알려주세요', NOW() - INTERVAL 14 DAY + INTERVAL 4 HOUR, NOW() - INTERVAL 14 DAY + INTERVAL 4 HOUR
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel') AND title = '제주 3박 4일 코스' ORDER BY id LIMIT 1) AND content = '사진 잘 나오는 곳도 알려주세요');
INSERT INTO comments (post_id, author_id, parent_id, reply_to_user_id, content, created_at, updated_at)
  SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel') AND title = '오사카 먹방 여행' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user02@example.com'), NULL, NULL, '쿠로몬 시장 가 보고 싶네요', NOW() - INTERVAL 9 DAY + INTERVAL 1 HOUR, NOW() - INTERVAL 9 DAY + INTERVAL 1 HOUR
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel') AND title = '오사카 먹방 여행' ORDER BY id LIMIT 1) AND content = '쿠로몬 시장 가 보고 싶네요');
INSERT INTO comments (post_id, author_id, parent_id, reply_to_user_id, content, created_at, updated_at)
  SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'doyun-dev') AND title = 'record는 언제 쓸까' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user09@example.com'), NULL, NULL, 'JPA 엔티티에는 못 쓰는 거 맞죠?', NOW() - INTERVAL 11 DAY + INTERVAL 1 HOUR, NOW() - INTERVAL 11 DAY + INTERVAL 1 HOUR
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'doyun-dev') AND title = 'record는 언제 쓸까' ORDER BY id LIMIT 1) AND content = 'JPA 엔티티에는 못 쓰는 거 맞죠?');
INSERT INTO comments (post_id, author_id, parent_id, reply_to_user_id, content, created_at, updated_at)
  SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'doyun-dev') AND title = 'record는 언제 쓸까' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user04@example.com'), (SELECT id FROM (SELECT id FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'doyun-dev') AND title = 'record는 언제 쓸까' ORDER BY id LIMIT 1) AND content = 'JPA 엔티티에는 못 쓰는 거 맞죠?' ORDER BY id LIMIT 1) x), (SELECT id FROM users WHERE email = 'user09@example.com'), '네, 기본 생성자와 setter가 필요해서요', NOW() - INTERVAL 11 DAY + INTERVAL 2 HOUR, NOW() - INTERVAL 11 DAY + INTERVAL 2 HOUR
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'doyun-dev') AND title = 'record는 언제 쓸까' ORDER BY id LIMIT 1) AND content = '네, 기본 생성자와 setter가 필요해서요');
INSERT INTO comments (post_id, author_id, parent_id, reply_to_user_id, content, created_at, updated_at)
  SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'doyun-dev') AND title = '@Transactional 기본 정리' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user15@example.com'), NULL, NULL, '자기 호출 부분 헷갈렸는데 정리 감사해요', NOW() - INTERVAL 7 DAY + INTERVAL 1 HOUR, NOW() - INTERVAL 7 DAY + INTERVAL 1 HOUR
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'doyun-dev') AND title = '@Transactional 기본 정리' ORDER BY id LIMIT 1) AND content = '자기 호출 부분 헷갈렸는데 정리 감사해요');
INSERT INTO comments (post_id, author_id, parent_id, reply_to_user_id, content, created_at, updated_at)
  SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'doyun-dev') AND title = '@Transactional 기본 정리' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user12@example.com'), NULL, NULL, 'readOnly 얘기도 추가해 주세요', NOW() - INTERVAL 7 DAY + INTERVAL 2 HOUR, NOW() - INTERVAL 7 DAY + INTERVAL 2 HOUR
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'doyun-dev') AND title = '@Transactional 기본 정리' ORDER BY id LIMIT 1) AND content = 'readOnly 얘기도 추가해 주세요');
INSERT INTO comments (post_id, author_id, parent_id, reply_to_user_id, content, created_at, updated_at)
  SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'hajun-photo') AND title = '새벽 한강' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user03@example.com'), NULL, NULL, '색감이 너무 좋아요', NOW() - INTERVAL 13 DAY + INTERVAL 1 HOUR, NOW() - INTERVAL 13 DAY + INTERVAL 1 HOUR
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'hajun-photo') AND title = '새벽 한강' ORDER BY id LIMIT 1) AND content = '색감이 너무 좋아요');
INSERT INTO comments (post_id, author_id, parent_id, reply_to_user_id, content, created_at, updated_at)
  SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'hajun-photo') AND title = '새벽 한강' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user07@example.com'), NULL, NULL, '삼각대 쓰셨어요?', NOW() - INTERVAL 13 DAY + INTERVAL 2 HOUR, NOW() - INTERVAL 13 DAY + INTERVAL 2 HOUR
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'hajun-photo') AND title = '새벽 한강' ORDER BY id LIMIT 1) AND content = '삼각대 쓰셨어요?');
INSERT INTO comments (post_id, author_id, parent_id, reply_to_user_id, content, created_at, updated_at)
  SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'hajun-photo') AND title = '새벽 한강' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user06@example.com'), (SELECT id FROM (SELECT id FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'hajun-photo') AND title = '새벽 한강' ORDER BY id LIMIT 1) AND content = '삼각대 쓰셨어요?' ORDER BY id LIMIT 1) x), (SELECT id FROM users WHERE email = 'user07@example.com'), '네, 1/30이라 필수예요', NOW() - INTERVAL 13 DAY + INTERVAL 3 HOUR, NOW() - INTERVAL 13 DAY + INTERVAL 3 HOUR
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'hajun-photo') AND title = '새벽 한강' ORDER BY id LIMIT 1) AND content = '네, 1/30이라 필수예요');
INSERT INTO comments (post_id, author_id, parent_id, reply_to_user_id, content, created_at, updated_at)
  SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'jiho-algo') AND title = 'BFS 기본 틀' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user15@example.com'), NULL, NULL, '방문 체크 위치가 핵심이네요', NOW() - INTERVAL 8 DAY + INTERVAL 1 HOUR, NOW() - INTERVAL 8 DAY + INTERVAL 1 HOUR
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'jiho-algo') AND title = 'BFS 기본 틀' ORDER BY id LIMIT 1) AND content = '방문 체크 위치가 핵심이네요');
INSERT INTO comments (post_id, author_id, parent_id, reply_to_user_id, content, created_at, updated_at)
  SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'jiho-algo') AND title = 'BFS 기본 틀' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user14@example.com'), (SELECT id FROM (SELECT id FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'jiho-algo') AND title = 'BFS 기본 틀' ORDER BY id LIMIT 1) AND content = '방문 체크 위치가 핵심이네요' ORDER BY id LIMIT 1) x), (SELECT id FROM users WHERE email = 'user15@example.com'), '꺼낼 때 체크하면 중복이 생기는군요', NOW() - INTERVAL 8 DAY + INTERVAL 2 HOUR, NOW() - INTERVAL 8 DAY + INTERVAL 2 HOUR
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'jiho-algo') AND title = 'BFS 기본 틀' ORDER BY id LIMIT 1) AND content = '꺼낼 때 체크하면 중복이 생기는군요');
INSERT INTO comments (post_id, author_id, parent_id, reply_to_user_id, content, created_at, updated_at)
  SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'yuna-eats') AND title = '을지로 노포 3곳' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user17@example.com'), NULL, NULL, '평양냉면집 이름 알려주세요!', NOW() - INTERVAL 10 DAY + INTERVAL 1 HOUR, NOW() - INTERVAL 10 DAY + INTERVAL 1 HOUR
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'yuna-eats') AND title = '을지로 노포 3곳' ORDER BY id LIMIT 1) AND content = '평양냉면집 이름 알려주세요!');
INSERT INTO comments (post_id, author_id, parent_id, reply_to_user_id, content, created_at, updated_at)
  SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'yuna-eats') AND title = '을지로 노포 3곳' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user16@example.com'), (SELECT id FROM (SELECT id FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'yuna-eats') AND title = '을지로 노포 3곳' ORDER BY id LIMIT 1) AND content = '평양냉면집 이름 알려주세요!' ORDER BY id LIMIT 1) x), (SELECT id FROM users WHERE email = 'user17@example.com'), '쪽지 드릴게요 ㅎㅎ', NOW() - INTERVAL 10 DAY + INTERVAL 2 HOUR, NOW() - INTERVAL 10 DAY + INTERVAL 2 HOUR
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'yuna-eats') AND title = '을지로 노포 3곳' ORDER BY id LIMIT 1) AND content = '쪽지 드릴게요 ㅎㅎ');
INSERT INTO comments (post_id, author_id, parent_id, reply_to_user_id, content, created_at, updated_at)
  SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'yuna-eats') AND title = '을지로 노포 3곳' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user08@example.com'), NULL, NULL, '골뱅이 골목 최고', NOW() - INTERVAL 10 DAY + INTERVAL 3 HOUR, NOW() - INTERVAL 10 DAY + INTERVAL 3 HOUR
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'yuna-eats') AND title = '을지로 노포 3곳' ORDER BY id LIMIT 1) AND content = '골뱅이 골목 최고');
INSERT INTO comments (post_id, author_id, parent_id, reply_to_user_id, content, created_at, updated_at)
  SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'yuna-eats') AND title = '해운대 돼지국밥' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user02@example.com'), NULL, NULL, '부산 가면 꼭 가볼게요', NOW() - INTERVAL 7 DAY + INTERVAL 1 HOUR, NOW() - INTERVAL 7 DAY + INTERVAL 1 HOUR
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM comments WHERE post_id = (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'yuna-eats') AND title = '해운대 돼지국밥' ORDER BY id LIMIT 1) AND content = '부산 가면 꼭 가볼게요');

-- 7. 좋아요
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND title = '된장찌개 황금 비율' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user03@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND title = '된장찌개 황금 비율' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user16@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND title = '된장찌개 황금 비율' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user10@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND title = '된장찌개 황금 비율' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user08@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND title = '된장찌개 황금 비율' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user05@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND title = '15분 크림 파스타' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user08@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'minjun-table') AND title = '15분 크림 파스타' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user03@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel') AND title = '제주 3박 4일 코스' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user06@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel') AND title = '제주 3박 4일 코스' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user11@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel') AND title = '제주 3박 4일 코스' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user13@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel') AND title = '제주 3박 4일 코스' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user02@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel') AND title = '제주 3박 4일 코스' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user07@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel') AND title = '제주 3박 4일 코스' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user12@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel') AND title = '오사카 먹방 여행' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user02@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'seoyeon-travel') AND title = '오사카 먹방 여행' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user06@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'doyun-dev') AND title = 'record는 언제 쓸까' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user09@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'doyun-dev') AND title = 'record는 언제 쓸까' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user15@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'doyun-dev') AND title = 'record는 언제 쓸까' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user12@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'doyun-dev') AND title = '@Transactional 기본 정리' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user04@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'doyun-dev') AND title = '@Transactional 기본 정리' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user15@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'hajun-photo') AND title = '새벽 한강' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user03@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'hajun-photo') AND title = '새벽 한강' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user07@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'hajun-photo') AND title = '새벽 한강' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user13@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'hajun-photo') AND title = '새벽 한강' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user02@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'hajun-photo') AND title = '새벽 한강' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user11@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'hajun-photo') AND title = '새벽 한강' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user14@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'hajun-photo') AND title = '새벽 한강' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user16@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'hajun-photo') AND title = '역광 인물 사진 팁' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user06@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'hajun-photo') AND title = '역광 인물 사진 팁' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user03@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'jiho-algo') AND title = 'BFS 기본 틀' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user04@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'jiho-algo') AND title = 'BFS 기본 틀' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user15@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'jiho-algo') AND title = 'BFS 기본 틀' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user14@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'yuna-eats') AND title = '을지로 노포 3곳' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user02@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'yuna-eats') AND title = '을지로 노포 3곳' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user18@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'yuna-eats') AND title = '을지로 노포 3곳' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user17@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'yuna-eats') AND title = '을지로 노포 3곳' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user08@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'yuna-eats') AND title = '성수 디저트 카페' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user16@example.com');
INSERT IGNORE INTO post_likes (post_id, user_id) SELECT (SELECT id FROM posts WHERE blog_id = (SELECT id FROM blogs WHERE slug = 'yuna-eats') AND title = '성수 디저트 카페' ORDER BY id LIMIT 1), (SELECT id FROM users WHERE email = 'user02@example.com');

-- 8. 숫자 맞추기 (멤버 수, 글 수, 댓글 수, 좋아요 수). updated_at은 그대로 둔다
UPDATE blogs b SET b.member_count = (SELECT COUNT(*) FROM blog_members m WHERE m.blog_id = b.id),
  b.post_count = (SELECT COUNT(*) FROM posts p WHERE p.blog_id = b.id AND p.status = 'PUBLISHED'),
  b.updated_at = b.updated_at;
UPDATE posts p SET p.comment_count = (SELECT COUNT(*) FROM comments c WHERE c.post_id = p.id AND c.status = 'ACTIVE'),
  p.like_count = (SELECT COUNT(*) FROM post_likes l WHERE l.post_id = p.id),
  p.updated_at = p.updated_at;

COMMIT;
