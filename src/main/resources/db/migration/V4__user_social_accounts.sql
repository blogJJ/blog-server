-- T157: 구글·깃허브 소셜 로그인 (D-128). blogJJ/blog-docs erd_tables.sql의 user_social_accounts, users.password_hash와 같게 맞춘다.
-- 블루그린 배포 중 옛 버전도 같은 DB를 쓰므로 테이블 추가와 NOT NULL 풀기만 한다. 옛 버전은 password_hash가 NULL인 회원을 비밀번호 틀림으로 본다.

-- 소셜 로그인으로만 가입한 회원은 비밀번호가 없다. 비밀번호 변경·찾기로 나중에 만들 수 있다
ALTER TABLE users
  MODIFY COLUMN password_hash VARCHAR(100) NULL COMMENT 'bcrypt 해시. 소셜 로그인으로만 가입해 비밀번호가 없으면 NULL (D-128)';

CREATE TABLE user_social_accounts (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '번호',
  user_id BIGINT NOT NULL COMMENT '연결된 회원',
  provider ENUM('GOOGLE','GITHUB') NOT NULL COMMENT '소셜 로그인 제공자',
  provider_user_id VARCHAR(100) NOT NULL COMMENT '제공자의 회원 번호 (구글 sub, 깃허브 id). 이메일이 바뀌어도 그대로',
  email_at_link VARCHAR(100) NULL COMMENT '연결할 때 제공자가 확인해 준 이메일 (소문자). 기록용',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '연결한 시각',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_social_accounts_provider_user (provider, provider_user_id),
  UNIQUE KEY uk_user_social_accounts_user_provider (user_id, provider),
  CONSTRAINT fk_user_social_accounts_user_id FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='소셜 로그인 연결 (회원당 제공자별 하나)';
