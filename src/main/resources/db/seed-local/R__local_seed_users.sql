-- 내 컴퓨터(local 프로필)에서만 넣는 시험용 회원 20명. 운영·테스트에는 들어가지 않는다 (application-local.yml의 flyway.locations).
-- Crowfoot 더미 DB(cf_u34_d1)와 같은 회원이고, 탈퇴 회원(다은)을 뺀 모두의 비밀번호는 test1234! 이다.
-- 이메일·닉네임이 이미 있으면 건너뛴다(INSERT IGNORE). 이 파일을 고치면 서버를 다시 켤 때 한 번 더 실행된다.

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
