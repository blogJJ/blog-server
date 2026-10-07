-- T006: blogJJ/blog-docs docs/02-database/schema/erd_tables.sql (main 4d34c37, D-107 기준)을 그대로 옮김.
-- 테이블·컬럼을 바꾸려면 blog-docs의 erd_tables.sql을 먼저 고치고, 여기는 고치지 말고 새 V 파일을 더한다.

-- 메인블로그 플랫폼 테이블 (MySQL 8). PK, UNIQUE, FK만 들어 있습니다.
-- 조회용 인덱스는 add_indexes.sql 에 따로 있습니다 (나중에 추가).

CREATE TABLE users (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  email VARCHAR(100) NULL COMMENT '로그인 아이디. 소문자로 저장. 탈퇴 즉시 NULL (같은 이메일로 바로 재가입)',
  password_hash VARCHAR(100) NOT NULL COMMENT 'bcrypt 해시',
  name VARCHAR(30) NULL COMMENT '이름. 바꿀 수 없음. 탈퇴 30일 뒤 NULL',
  nickname VARCHAR(12) NULL COMMENT '2~12자. 탈퇴 30일 뒤 NULL',
  phone VARCHAR(11) NULL COMMENT '숫자만. 1차는 중복 허용. 탈퇴 30일 뒤 NULL',
  profile_image VARCHAR(255) NULL COMMENT '프로필 사진 저장 경로 (UUID 이름)',
  bio VARCHAR(200) NULL COMMENT '소개',
  role ENUM('USER','ADMIN') NOT NULL DEFAULT 'USER' COMMENT 'ADMIN = 메인 관리자 (블로그 활동은 못 함, D-90)',
  status ENUM('ACTIVE','WITHDRAWN') NOT NULL DEFAULT 'ACTIVE' COMMENT '탈퇴하면 행을 지우지 않고 WITHDRAWN',
  suspended_until DATETIME NULL COMMENT '메인 관리자 계정 정지가 끝나는 시각 (ADM-08). 영구 정지는 9999-12-31. 정지 아니면 NULL',
  login_fail_count INT NOT NULL DEFAULT 0 COMMENT '연속 로그인 실패 횟수 (5회면 잠금, 3회부터 CAPTCHA)',
  locked_until DATETIME NULL COMMENT '로그인 잠금 풀리는 시각 (5분)',
  notification_keep_days TINYINT NOT NULL DEFAULT 30 COMMENT '알림 보관 일수 (30 또는 7)',
  terms_agreed_at DATETIME NOT NULL COMMENT '이용약관 동의 시각',
  privacy_agreed_at DATETIME NOT NULL COMMENT '개인정보 수집·이용 동의 시각',
  withdrawn_at DATETIME NULL COMMENT '탈퇴 시각 (30일 뒤 개인정보 삭제 기준)',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '만든 시각',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '고친 시각 (ON UPDATE)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_users_email (email),
  UNIQUE KEY uk_users_nickname (nickname)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='회원';

CREATE TABLE verification_codes (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  email VARCHAR(100) NOT NULL COMMENT '가입 전이라 회원 번호 대신 이메일 기준',
  purpose ENUM('SIGNUP','PASSWORD_RESET') NOT NULL COMMENT '가입 인증 / 비밀번호 재설정',
  code_hash VARCHAR(100) NOT NULL COMMENT '6자리 번호의 해시',
  fail_count TINYINT NOT NULL DEFAULT 0 COMMENT '5번 틀리면 무효',
  expires_at DATETIME NOT NULL COMMENT '가입 10분, 재설정 30분',
  verified_at DATETIME NULL COMMENT '맞게 입력한 시각 (가입 마지막 단계에서 다시 확인)',
  used_at DATETIME NULL COMMENT '사용한 시각 (1회용)',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '만든 시각',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='이메일 인증번호';

CREATE TABLE account_find_tokens (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  user_id BIGINT NOT NULL COMMENT '찾은 계정',
  token_hash VARCHAR(100) NOT NULL COMMENT '임시 토큰 해시 (비밀번호 재설정 요청에 씀)',
  expires_at DATETIME NOT NULL COMMENT '10분',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '만든 시각',
  PRIMARY KEY (id),
  UNIQUE KEY uk_account_find_tokens_token_hash (token_hash),
  CONSTRAINT fk_account_find_tokens_user_id FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='이메일 찾기 임시 토큰';

CREATE TABLE refresh_tokens (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  user_id BIGINT NOT NULL COMMENT '토큰 주인',
  token_hash VARCHAR(100) NOT NULL COMMENT '토큰 해시 (원문 저장 안 함)',
  remember_me BOOLEAN NOT NULL DEFAULT FALSE COMMENT '"로그인 유지" 체크 여부 (14일 / 30분)',
  user_agent VARCHAR(255) NULL COMMENT '기기 구분용',
  expires_at DATETIME NOT NULL COMMENT '만료 시각',
  revoked_at DATETIME NULL COMMENT '로그아웃·비밀번호 변경 때 폐기한 시각',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '만든 시각',
  PRIMARY KEY (id),
  UNIQUE KEY uk_refresh_tokens_token_hash (token_hash),
  CONSTRAINT fk_refresh_tokens_user_id FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Refresh Token';

CREATE TABLE blogs (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  owner_id BIGINT NOT NULL COMMENT '블로그장 (blog_members의 OWNER와 같은 사람)',
  slug VARCHAR(30) NULL COMMENT '블로그 주소 /blog/{slug}. 영문 소문자·숫자·-. 폐쇄 30일 뒤 NULL로 비워 다른 사람이 쓸 수 있게 함 (D-86)',
  name VARCHAR(50) NOT NULL COMMENT '블로그 이름 (중복 허용)',
  description VARCHAR(500) NULL COMMENT '소개',
  cover_image VARCHAR(255) NULL COMMENT '대표 이미지 경로',
  visibility ENUM('PUBLIC','LINK_ONLY','PRIVATE') NOT NULL DEFAULT 'PUBLIC' COMMENT '공개 / 일부 공개 / 비공개',
  share_key VARCHAR(64) NULL COMMENT '일부 공개 공유 링크의 무작위 값. 새로 만들면 이전 값 무효',
  join_policy ENUM('OPEN','APPROVAL') NOT NULL DEFAULT 'OPEN' COMMENT '자유 참여 / 승인제',
  status ENUM('ACTIVE','CLOSING','CLOSED') NOT NULL DEFAULT 'ACTIVE' COMMENT '운영 중 / 폐쇄 예정(7일) / 폐쇄',
  is_hidden BOOLEAN NOT NULL DEFAULT FALSE COMMENT '관리자가 숨김 (ADM-02)',
  close_scheduled_at DATETIME NULL COMMENT '폐쇄 예정 시각 (7일 뒤 04:00). 철회하면 NULL',
  close_reason ENUM('OWNER','OWNER_DEMOTED','ADMIN') NULL COMMENT '블로그장 폐쇄 / 블로그장 박탈 / 관리자 강제 폐쇄',
  closed_at DATETIME NULL COMMENT '폐쇄된 시각 (30일 뒤 글·사진·카테고리 삭제, 행은 남김)',
  member_count INT NOT NULL DEFAULT 1 COMMENT '멤버 수 (인기순 정렬용)',
  post_count INT NOT NULL DEFAULT 0 COMMENT '글 수',
  subscriber_count INT NOT NULL DEFAULT 0 COMMENT '구독자 수',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '만든 시각',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '고친 시각 (ON UPDATE)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_blogs_slug (slug),
  UNIQUE KEY uk_blogs_share_key (share_key),
  CONSTRAINT fk_blogs_owner_id FOREIGN KEY (owner_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='블로그';

CREATE TABLE blog_members (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  blog_id BIGINT NOT NULL COMMENT '블로그',
  user_id BIGINT NOT NULL COMMENT '회원',
  role ENUM('OWNER','MANAGER','MEMBER') NOT NULL DEFAULT 'MEMBER' COMMENT '블로그장 / 부블로그장 / 멤버',
  manager_since DATETIME NULL COMMENT '부블로그장이 된 시각 (자동 위임 순서, ADM-07)',
  suspended_until DATETIME NULL COMMENT '정지 끝나는 시각. 영구 정지는 9999-12-31. 정지 아니면 NULL',
  suspension_count INT NOT NULL DEFAULT 0 COMMENT '이 블로그에서 받은 정지 횟수 (3번이면 블로그장 화면에 표시)',
  joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '참여 시각',
  PRIMARY KEY (id),
  UNIQUE KEY uk_blog_members (blog_id, user_id),
  CONSTRAINT fk_blog_members_blog_id FOREIGN KEY (blog_id) REFERENCES blogs (id),
  CONSTRAINT fk_blog_members_user_id FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='블로그 멤버';

CREATE TABLE blog_manager_permissions (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  blog_member_id BIGINT NOT NULL COMMENT '부블로그장인 멤버 행',
  permission ENUM('EDIT_INFO','MANAGE_MEMBERS','MANAGE_POSTS') NOT NULL COMMENT '블로그 정보 수정 / 멤버 관리 / 글 관리',
  granted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '권한 준 시각',
  PRIMARY KEY (id),
  UNIQUE KEY uk_manager_perm (blog_member_id, permission),
  CONSTRAINT fk_blog_manager_permissions_blog_member_id FOREIGN KEY (blog_member_id) REFERENCES blog_members (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='부블로그장 권한';

CREATE TABLE blog_join_requests (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  blog_id BIGINT NOT NULL COMMENT '신청한 블로그',
  user_id BIGINT NOT NULL COMMENT '신청한 회원',
  status ENUM('PENDING','APPROVED','REJECTED','CANCELED') NOT NULL DEFAULT 'PENDING' COMMENT '대기 / 승인 / 거절 / 신청 취소',
  handled_by BIGINT NULL COMMENT '처리한 블로그장·부블로그장',
  handled_at DATETIME NULL COMMENT '처리 시각 (거절 7일 뒤 재신청 기준)',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '만든 시각',
  PRIMARY KEY (id),
  CONSTRAINT fk_blog_join_requests_blog_id FOREIGN KEY (blog_id) REFERENCES blogs (id),
  CONSTRAINT fk_blog_join_requests_user_id FOREIGN KEY (user_id) REFERENCES users (id),
  CONSTRAINT fk_blog_join_requests_handled_by FOREIGN KEY (handled_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='참여 신청';

CREATE TABLE blog_owner_transfers (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  blog_id BIGINT NOT NULL COMMENT '블로그',
  from_user_id BIGINT NOT NULL COMMENT '요청한 블로그장',
  to_user_id BIGINT NOT NULL COMMENT '받는 멤버',
  status ENUM('PENDING','ACCEPTED','REJECTED','CANCELED','EXPIRED') NOT NULL DEFAULT 'PENDING' COMMENT '대기 / 수락 / 거절 / 폐쇄로 취소 / 7일 지나 자동 취소',
  expires_at DATETIME NOT NULL COMMENT '요청 + 7일',
  responded_at DATETIME NULL COMMENT '응답 시각',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '만든 시각',
  PRIMARY KEY (id),
  CONSTRAINT fk_blog_owner_transfers_blog_id FOREIGN KEY (blog_id) REFERENCES blogs (id),
  CONSTRAINT fk_blog_owner_transfers_from_user_id FOREIGN KEY (from_user_id) REFERENCES users (id),
  CONSTRAINT fk_blog_owner_transfers_to_user_id FOREIGN KEY (to_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='블로그장 위임 요청';

CREATE TABLE blog_subscriptions (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  blog_id BIGINT NOT NULL COMMENT '구독한 블로그',
  user_id BIGINT NOT NULL COMMENT '구독한 회원',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '만든 시각',
  PRIMARY KEY (id),
  UNIQUE KEY uk_blog_subs (blog_id, user_id),
  CONSTRAINT fk_blog_subscriptions_blog_id FOREIGN KEY (blog_id) REFERENCES blogs (id),
  CONSTRAINT fk_blog_subscriptions_user_id FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='블로그 구독';

CREATE TABLE blog_close_notices (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  blog_id BIGINT NOT NULL COMMENT '블로그',
  close_scheduled_at DATETIME NOT NULL COMMENT '어느 폐쇄 예정에 대한 알림인지 (철회 후 다시 누르면 새 값)',
  stage ENUM('IMMEDIATE','D3','D1','REVOKED') NOT NULL COMMENT '즉시 / 3일 전 / 1일 전 / 철회',
  sent_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '보낸 시각',
  PRIMARY KEY (id),
  UNIQUE KEY uk_close_notice (blog_id, close_scheduled_at, stage),
  CONSTRAINT fk_blog_close_notices_blog_id FOREIGN KEY (blog_id) REFERENCES blogs (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='폐쇄 알림 발송 기록';

CREATE TABLE categories (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  blog_id BIGINT NOT NULL COMMENT '블로그',
  name VARCHAR(30) NOT NULL COMMENT '카테고리 이름',
  sort_order INT NOT NULL DEFAULT 0 COMMENT '표시 순서',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '만든 시각',
  PRIMARY KEY (id),
  UNIQUE KEY uk_categories (blog_id, name),
  CONSTRAINT fk_categories_blog_id FOREIGN KEY (blog_id) REFERENCES blogs (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='카테고리';

CREATE TABLE tags (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  name VARCHAR(30) NOT NULL COMMENT '태그 이름 (영문은 소문자, 1~20자)',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '만든 시각',
  PRIMARY KEY (id),
  UNIQUE KEY uk_tags_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='태그';

CREATE TABLE blog_tags (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  blog_id BIGINT NOT NULL COMMENT '블로그',
  tag_id BIGINT NOT NULL COMMENT '태그',
  PRIMARY KEY (id),
  UNIQUE KEY uk_blog_tags (blog_id, tag_id),
  CONSTRAINT fk_blog_tags_blog_id FOREIGN KEY (blog_id) REFERENCES blogs (id),
  CONSTRAINT fk_blog_tags_tag_id FOREIGN KEY (tag_id) REFERENCES tags (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='블로그 태그';

CREATE TABLE reports (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  reporter_id BIGINT NOT NULL COMMENT '신고한 회원',
  target_type ENUM('USER','BLOG','POST','COMMENT') NOT NULL COMMENT '신고 대상 종류',
  target_id BIGINT NOT NULL COMMENT '대상 번호 (종류마다 테이블이 달라 FK 없음)',
  blog_id BIGINT NULL COMMENT '블로그 안의 신고면 그 블로그 (블로그장이 처리)',
  handler_scope ENUM('BLOG_OWNER','ADMIN') NOT NULL COMMENT '처리할 사람. 블로그장 본인·블로그장 글·댓글, 메인 프로필, 블로그장 정지 중인 블로그의 신고는 ADMIN',
  reason ENUM('SPAM','ABUSE','ADULT','ILLEGAL','ETC') NOT NULL COMMENT '사유',
  detail VARCHAR(500) NULL COMMENT '자세한 내용',
  target_snapshot VARCHAR(1000) NOT NULL COMMENT '신고 당시 대상 내용 (글 제목·본문 앞부분, 댓글 내용, 닉네임, 블로그 이름). 원본이 지워져도 1년 동안 확인',
  status ENUM('PENDING','NO_ISSUE','WARNED','SUSPENDED','KICKED','OWNER_DEMOTED','BLOG_CLOSED','PROFILE_RESET','ACCOUNT_SUSPENDED') NOT NULL DEFAULT 'PENDING' COMMENT '처리 결과',
  handled_by BIGINT NULL COMMENT '처리한 사람',
  handled_at DATETIME NULL COMMENT '처리 시각',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '만든 시각',
  PRIMARY KEY (id),
  CONSTRAINT fk_reports_reporter_id FOREIGN KEY (reporter_id) REFERENCES users (id),
  CONSTRAINT fk_reports_blog_id FOREIGN KEY (blog_id) REFERENCES blogs (id),
  CONSTRAINT fk_reports_handled_by FOREIGN KEY (handled_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='신고';

CREATE TABLE member_sanctions (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  blog_id BIGINT NOT NULL COMMENT '블로그',
  user_id BIGINT NOT NULL COMMENT '대상 멤버',
  type ENUM('WARNING','SUSPENSION','KICK') NOT NULL COMMENT '경고 / 정지 / 강제 퇴장',
  suspend_days SMALLINT NULL COMMENT '3, 14, 30. 영구는 NULL (정지일 때만)',
  ends_at DATETIME NULL COMMENT '정지 끝나는 시각',
  reason VARCHAR(500) NOT NULL COMMENT '사유',
  report_id BIGINT NULL COMMENT '신고를 처리하며 준 경우 그 신고',
  issued_by BIGINT NOT NULL COMMENT '조치한 블로그장·부블로그장 (블로그장 정지 중이면 메인 관리자, ADM-08)',
  released_at DATETIME NULL COMMENT '정지 해제 시각',
  released_by BIGINT NULL COMMENT '해제한 사람',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '만든 시각',
  PRIMARY KEY (id),
  CONSTRAINT fk_member_sanctions_blog_id FOREIGN KEY (blog_id) REFERENCES blogs (id),
  CONSTRAINT fk_member_sanctions_user_id FOREIGN KEY (user_id) REFERENCES users (id),
  CONSTRAINT fk_member_sanctions_report_id FOREIGN KEY (report_id) REFERENCES reports (id),
  CONSTRAINT fk_member_sanctions_issued_by FOREIGN KEY (issued_by) REFERENCES users (id),
  CONSTRAINT fk_member_sanctions_released_by FOREIGN KEY (released_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='멤버 경고·정지·강제 퇴장';

CREATE TABLE owner_sanctions (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  blog_id BIGINT NOT NULL COMMENT '블로그',
  user_id BIGINT NOT NULL COMMENT '대상 블로그장',
  type ENUM('WARNING','DEMOTION') NOT NULL COMMENT '경고 / 권한 박탈',
  reason VARCHAR(500) NOT NULL COMMENT '사유',
  report_id BIGINT NULL COMMENT '신고를 처리하며 준 경우',
  admin_id BIGINT NOT NULL COMMENT '조치한 메인 관리자',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '만든 시각',
  PRIMARY KEY (id),
  CONSTRAINT fk_owner_sanctions_blog_id FOREIGN KEY (blog_id) REFERENCES blogs (id),
  CONSTRAINT fk_owner_sanctions_user_id FOREIGN KEY (user_id) REFERENCES users (id),
  CONSTRAINT fk_owner_sanctions_report_id FOREIGN KEY (report_id) REFERENCES reports (id),
  CONSTRAINT fk_owner_sanctions_admin_id FOREIGN KEY (admin_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='블로그장 경고·권한 박탈';

CREATE TABLE user_sanctions (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  user_id BIGINT NOT NULL COMMENT '대상 회원',
  type ENUM('WARNING','PROFILE_RESET','SUSPENSION') NOT NULL COMMENT '경고 / 프로필 초기화 / 계정 정지',
  suspend_days SMALLINT NULL COMMENT '3, 14, 30. 영구는 NULL (정지일 때만)',
  ends_at DATETIME NULL COMMENT '정지 끝나는 시각',
  reason VARCHAR(500) NOT NULL COMMENT '사유',
  report_id BIGINT NULL COMMENT '신고를 처리하며 준 경우 그 신고',
  admin_id BIGINT NOT NULL COMMENT '조치한 메인 관리자',
  released_at DATETIME NULL COMMENT '정지 해제 시각',
  released_by BIGINT NULL COMMENT '해제한 관리자',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '만든 시각',
  PRIMARY KEY (id),
  CONSTRAINT fk_user_sanctions_user_id FOREIGN KEY (user_id) REFERENCES users (id),
  CONSTRAINT fk_user_sanctions_report_id FOREIGN KEY (report_id) REFERENCES reports (id),
  CONSTRAINT fk_user_sanctions_admin_id FOREIGN KEY (admin_id) REFERENCES users (id),
  CONSTRAINT fk_user_sanctions_released_by FOREIGN KEY (released_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='계정 경고·프로필 초기화·정지 (메인 프로필 신고, ADM-08)';

CREATE TABLE blog_blacklist (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  blog_id BIGINT NOT NULL COMMENT '블로그',
  user_id BIGINT NULL COMMENT '강퇴된 회원 (탈퇴하면 NULL이 될 수 있음)',
  name_hash VARCHAR(100) NOT NULL COMMENT '이름 해시',
  email_hash VARCHAR(100) NOT NULL COMMENT '이메일 해시',
  phone_hash VARCHAR(100) NOT NULL COMMENT '전화번호 해시',
  registered_by BIGINT NOT NULL COMMENT '등록한 블로그장·부블로그장',
  released_at DATETIME NULL COMMENT '해제 시각 (기록은 고치거나 지우지 않음)',
  released_by BIGINT NULL COMMENT '해제한 사람',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '만든 시각',
  PRIMARY KEY (id),
  CONSTRAINT fk_blog_blacklist_blog_id FOREIGN KEY (blog_id) REFERENCES blogs (id),
  CONSTRAINT fk_blog_blacklist_user_id FOREIGN KEY (user_id) REFERENCES users (id),
  CONSTRAINT fk_blog_blacklist_registered_by FOREIGN KEY (registered_by) REFERENCES users (id),
  CONSTRAINT fk_blog_blacklist_released_by FOREIGN KEY (released_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='블로그 블랙리스트';

CREATE TABLE blacklist_inquiries (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  blacklist_id BIGINT NOT NULL COMMENT '걸린 블랙리스트 기록',
  user_id BIGINT NOT NULL COMMENT '문의한 회원',
  name_match BOOLEAN NOT NULL COMMENT '서버가 비교한 이름 일치 여부',
  phone_match BOOLEAN NOT NULL COMMENT '전화번호 일치 여부',
  message VARCHAR(500) NULL COMMENT '문의 내용',
  status ENUM('PENDING','RELEASED','REJECTED') NOT NULL DEFAULT 'PENDING' COMMENT '대기 / 해제 / 거절',
  handled_by BIGINT NULL COMMENT '처리한 블로그장',
  handled_at DATETIME NULL COMMENT '처리 시각',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '만든 시각',
  PRIMARY KEY (id),
  CONSTRAINT fk_blacklist_inquiries_blacklist_id FOREIGN KEY (blacklist_id) REFERENCES blog_blacklist (id),
  CONSTRAINT fk_blacklist_inquiries_user_id FOREIGN KEY (user_id) REFERENCES users (id),
  CONSTRAINT fk_blacklist_inquiries_handled_by FOREIGN KEY (handled_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='블랙리스트 해제 문의';

CREATE TABLE admin_action_logs (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  admin_id BIGINT NOT NULL COMMENT '조치한 관리자',
  action VARCHAR(50) NOT NULL COMMENT '예: OWNER_WARN, OWNER_DEMOTE, CLOSE_BLOG, HIDE_POST, VIEW_PRIVATE_INFO',
  target_type ENUM('USER','BLOG','POST','COMMENT','REPORT') NOT NULL COMMENT '대상 종류',
  target_id BIGINT NOT NULL COMMENT '대상 번호',
  reason VARCHAR(500) NULL COMMENT '사유',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '만든 시각',
  PRIMARY KEY (id),
  CONSTRAINT fk_admin_action_logs_admin_id FOREIGN KEY (admin_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='관리자 활동 기록';

CREATE TABLE posts (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  blog_id BIGINT NOT NULL COMMENT '블로그 (메인 공지는 main 블로그)',
  author_id BIGINT NOT NULL COMMENT '작성자',
  category_id BIGINT NULL COMMENT '카테고리 (없을 수 있음)',
  title VARCHAR(30) NOT NULL COMMENT '제목 1~30자',
  content TEXT NOT NULL COMMENT '본문 마크다운 (최대 5,000자, 서버에서 검사, D-92)',
  is_notice BOOLEAN NOT NULL DEFAULT FALSE COMMENT '블로그 공지',
  status ENUM('PUBLISHED','HIDDEN','DELETED') NOT NULL DEFAULT 'PUBLISHED' COMMENT '게시 / 관리자 숨김 / 삭제(30일 보관)',
  author_hidden BOOLEAN NOT NULL DEFAULT FALSE COMMENT '작성자가 블로그를 떠나거나 강퇴되면 "탈퇴한 계정"으로 표시',
  view_count INT NOT NULL DEFAULT 0 COMMENT '조회수',
  like_count INT NOT NULL DEFAULT 0 COMMENT '좋아요 수',
  comment_count INT NOT NULL DEFAULT 0 COMMENT '댓글 수',
  deleted_by BIGINT NULL COMMENT '삭제한 사람 (본인·블로그장·관리자)',
  deleted_at DATETIME NULL COMMENT '삭제 시각 (30일 뒤 완전 삭제)',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '만든 시각',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '고친 시각 (ON UPDATE)',
  PRIMARY KEY (id),
  CONSTRAINT fk_posts_blog_id FOREIGN KEY (blog_id) REFERENCES blogs (id),
  CONSTRAINT fk_posts_author_id FOREIGN KEY (author_id) REFERENCES users (id),
  CONSTRAINT fk_posts_category_id FOREIGN KEY (category_id) REFERENCES categories (id),
  CONSTRAINT fk_posts_deleted_by FOREIGN KEY (deleted_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='게시글';

CREATE TABLE post_tags (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  post_id BIGINT NOT NULL COMMENT '글',
  tag_id BIGINT NOT NULL COMMENT '태그',
  PRIMARY KEY (id),
  UNIQUE KEY uk_post_tags (post_id, tag_id),
  CONSTRAINT fk_post_tags_post_id FOREIGN KEY (post_id) REFERENCES posts (id),
  CONSTRAINT fk_post_tags_tag_id FOREIGN KEY (tag_id) REFERENCES tags (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='글-태그 연결';

CREATE TABLE post_images (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  post_id BIGINT NULL COMMENT '글 (글 저장 전 업로드면 NULL)',
  uploader_id BIGINT NOT NULL COMMENT '올린 회원',
  stored_name VARCHAR(64) NOT NULL COMMENT '서버에 저장한 UUID 이름',
  original_name VARCHAR(255) NOT NULL COMMENT '원래 파일 이름 (DB에만)',
  content_type VARCHAR(30) NOT NULL COMMENT 'MIME 타입',
  size_bytes INT NOT NULL COMMENT '크기 (장당 3MB 이하)',
  sort_order TINYINT NOT NULL DEFAULT 0 COMMENT '글 안 순서 (첫 이미지가 공유 미리보기)',
  deleted_at DATETIME NULL COMMENT '삭제 시각 (30일 보관)',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '만든 시각',
  PRIMARY KEY (id),
  UNIQUE KEY uk_post_images_stored_name (stored_name),
  CONSTRAINT fk_post_images_post_id FOREIGN KEY (post_id) REFERENCES posts (id),
  CONSTRAINT fk_post_images_uploader_id FOREIGN KEY (uploader_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='글 이미지';

CREATE TABLE comments (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  post_id BIGINT NOT NULL COMMENT '글',
  author_id BIGINT NOT NULL COMMENT '작성자',
  parent_id BIGINT NULL COMMENT '대댓글이면 부모 댓글 (1단계까지)',
  reply_to_user_id BIGINT NULL COMMENT '대댓글이 답하는 회원 (@닉네임 표시, D-87)',
  content VARCHAR(500) NOT NULL COMMENT '1~500자',
  status ENUM('ACTIVE','HIDDEN','DELETED') NOT NULL DEFAULT 'ACTIVE' COMMENT '답글 있는 댓글을 지우면 DELETED로 두고 "삭제된 댓글입니다" 표시',
  is_edited BOOLEAN NOT NULL DEFAULT FALSE COMMENT '"수정됨" 표시',
  deleted_at DATETIME NULL COMMENT '삭제 시각',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '만든 시각',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '고친 시각 (ON UPDATE)',
  PRIMARY KEY (id),
  CONSTRAINT fk_comments_post_id FOREIGN KEY (post_id) REFERENCES posts (id),
  CONSTRAINT fk_comments_author_id FOREIGN KEY (author_id) REFERENCES users (id),
  CONSTRAINT fk_comments_parent_id FOREIGN KEY (parent_id) REFERENCES comments (id),
  CONSTRAINT fk_comments_reply_to_user_id FOREIGN KEY (reply_to_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='댓글';

CREATE TABLE post_likes (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  post_id BIGINT NOT NULL COMMENT '글',
  user_id BIGINT NOT NULL COMMENT '누른 회원',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '만든 시각',
  PRIMARY KEY (id),
  UNIQUE KEY uk_post_likes (post_id, user_id),
  CONSTRAINT fk_post_likes_post_id FOREIGN KEY (post_id) REFERENCES posts (id),
  CONSTRAINT fk_post_likes_user_id FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='좋아요';

CREATE TABLE post_views (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  post_id BIGINT NOT NULL COMMENT '글',
  viewer_key VARCHAR(64) NOT NULL COMMENT '회원은 회원 번호, 비회원은 IP+브라우저 해시',
  view_date DATE NOT NULL COMMENT '본 날짜',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '만든 시각',
  PRIMARY KEY (id),
  UNIQUE KEY uk_post_views (post_id, viewer_key, view_date),
  CONSTRAINT fk_post_views_post_id FOREIGN KEY (post_id) REFERENCES posts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='조회 기록';

CREATE TABLE user_follows (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  follower_id BIGINT NOT NULL COMMENT '팔로우하는 회원',
  followee_id BIGINT NOT NULL COMMENT '팔로우받는 회원',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '만든 시각',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_follows (follower_id, followee_id),
  CONSTRAINT fk_user_follows_follower_id FOREIGN KEY (follower_id) REFERENCES users (id),
  CONSTRAINT fk_user_follows_followee_id FOREIGN KEY (followee_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='회원 팔로우';

CREATE TABLE user_blocks (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  blocker_id BIGINT NOT NULL COMMENT '차단한 회원',
  blocked_id BIGINT NOT NULL COMMENT '차단당한 회원',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '만든 시각',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_blocks (blocker_id, blocked_id),
  CONSTRAINT fk_user_blocks_blocker_id FOREIGN KEY (blocker_id) REFERENCES users (id),
  CONSTRAINT fk_user_blocks_blocked_id FOREIGN KEY (blocked_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='회원 차단';

CREATE TABLE notifications (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  receiver_id BIGINT NOT NULL COMMENT '받는 회원',
  actor_id BIGINT NULL COMMENT '알림을 일으킨 회원 (시스템 알림은 NULL)',
  tab ENUM('COMMENT','LIKE','FOLLOW','BLOG','OPERATION') NOT NULL COMMENT '사이드바 탭',
  type VARCHAR(40) NOT NULL COMMENT '알림 종류 (예: COMMENT, BLOG_CLOSING, OWNER_TRANSFER_REQUEST)',
  target_type ENUM('USER','BLOG','POST','COMMENT','REPORT') NULL COMMENT '누르면 갈 대상 종류',
  target_id BIGINT NULL COMMENT '대상 번호',
  message VARCHAR(255) NOT NULL COMMENT '표시할 문구',
  is_read BOOLEAN NOT NULL DEFAULT FALSE COMMENT '읽음 여부',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '만든 시각',
  PRIMARY KEY (id),
  CONSTRAINT fk_notifications_receiver_id FOREIGN KEY (receiver_id) REFERENCES users (id),
  CONSTRAINT fk_notifications_actor_id FOREIGN KEY (actor_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='알림';

CREATE TABLE notification_settings (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  user_id BIGINT NOT NULL COMMENT '회원',
  type VARCHAR(40) NOT NULL COMMENT '끌 수 있는 알림 종류',
  enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '켜짐 여부',
  PRIMARY KEY (id),
  UNIQUE KEY uk_notif_settings (user_id, type),
  CONSTRAINT fk_notification_settings_user_id FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='알림 설정';

CREATE TABLE recent_searches (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  user_id BIGINT NOT NULL COMMENT '회원 (본인만 봄)',
  keyword VARCHAR(20) NOT NULL COMMENT '검색어 2~20자',
  searched_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '검색 시각 (같은 검색어면 시각만 갱신)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_recent_search (user_id, keyword),
  CONSTRAINT fk_recent_searches_user_id FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='최근 검색어';

CREATE TABLE shedlock (
  name VARCHAR(64) NOT NULL COMMENT '작업 이름',
  lock_until TIMESTAMP(3) NOT NULL COMMENT '잠금 끝',
  locked_at TIMESTAMP(3) NOT NULL COMMENT '잠근 시각',
  locked_by VARCHAR(255) NOT NULL COMMENT '잠근 서버',
  PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='배치 잠금 (ShedLock)';
