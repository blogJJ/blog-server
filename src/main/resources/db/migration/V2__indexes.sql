-- T007: blogJJ/blog-docs docs/02-database/schema/add_indexes.sql (main 4d34c37)을 그대로 옮김.
-- FULLTEXT ngram: ft_blogs_search(블로그 이름·소개), ft_posts_title(글 제목) (BRD-08)

-- 나중에 추가할 조회용 인덱스 (MySQL 8)
-- FK 컬럼은 MySQL이 인덱스를 자동으로 만들지만, 아래 복합 인덱스가 같은 컬럼으로 시작하면 그걸로 대신합니다.

-- users (회원)
CREATE INDEX idx_users_find_email ON users (name, phone);  -- 이메일 찾기 (USR-08): 이름 + 전화번호
CREATE INDEX idx_users_created ON users (created_at);  -- 일별 가입 통계 (ADM-05)
CREATE INDEX idx_users_withdrawn ON users (status, withdrawn_at);  -- 새벽 4시 배치: 탈퇴 30일 지난 회원 찾기

-- verification_codes (이메일 인증번호)
CREATE INDEX idx_verif_email ON verification_codes (email, purpose, created_at);  -- 가장 최근 번호 찾기, 1분 재발송 제한
CREATE INDEX idx_verif_expires ON verification_codes (expires_at);  -- 만료된 번호 삭제

-- account_find_tokens (이메일 찾기 임시 토큰)
CREATE INDEX idx_find_tokens_expires ON account_find_tokens (expires_at);  -- 만료 토큰 삭제

-- refresh_tokens (Refresh Token)
CREATE INDEX idx_refresh_user ON refresh_tokens (user_id, revoked_at);  -- 비밀번호 변경 때 그 회원의 토큰 모두 폐기
CREATE INDEX idx_refresh_expires ON refresh_tokens (expires_at);  -- 새벽 4시 배치: 만료 토큰 삭제 (D-83)

-- blogs (블로그)
CREATE INDEX idx_blogs_list ON blogs (visibility, status, created_at);  -- 메인 블로그 목록 최신순 (BLG-02)
CREATE INDEX idx_blogs_popular ON blogs (visibility, status, member_count);  -- 메인 블로그 목록 인기순 = 멤버 수 (BLG-02, D-89)
CREATE INDEX idx_blogs_owner ON blogs (owner_id, visibility, status);  -- 생성 개수 세기 (BLG-10)
CREATE INDEX idx_blogs_close ON blogs (status, close_scheduled_at);  -- 새벽 4시 배치: 폐쇄할 블로그·3일 전·1일 전 알림
CREATE INDEX idx_blogs_closed ON blogs (status, closed_at);  -- 새벽 4시 배치: 폐쇄 30일 지난 블로그 내용 삭제
CREATE FULLTEXT INDEX ft_blogs_search ON blogs (name, description) WITH PARSER ngram;  -- 블로그 검색 (BLG-03, BRD-08), ngram

-- blog_members (블로그 멤버)
CREATE INDEX idx_blog_members_user ON blog_members (user_id, role);  -- 내 블로그 목록 (BLG-06), 탈퇴 전 운영 중인 블로그 확인
CREATE INDEX idx_blog_members_role ON blog_members (blog_id, role, manager_since);  -- 부블로그장 찾기, 자동 위임 대상

-- blog_join_requests (참여 신청)
CREATE INDEX idx_join_req_blog ON blog_join_requests (blog_id, status, created_at);  -- 블로그장이 보는 대기 신청 목록
CREATE INDEX idx_join_req_user ON blog_join_requests (user_id, blog_id, status);  -- 대기 중 신청·거절 7일 확인

-- blog_owner_transfers (블로그장 위임 요청)
CREATE INDEX idx_transfer_blog ON blog_owner_transfers (blog_id, status);  -- 대기 중 요청 확인 (블로그당 1개)
CREATE INDEX idx_transfer_to ON blog_owner_transfers (to_user_id, status);  -- 받은 위임 요청
CREATE INDEX idx_transfer_expires ON blog_owner_transfers (status, expires_at);  -- 새벽 4시 배치: 7일 지난 요청 자동 취소

-- blog_subscriptions (블로그 구독)
CREATE INDEX idx_blog_subs_user ON blog_subscriptions (user_id, created_at);  -- 내 구독 목록, 메인 피드 구독 탭

-- blog_tags (블로그 태그)
CREATE INDEX idx_blog_tags_tag ON blog_tags (tag_id);  -- 태그로 블로그 찾기

-- member_sanctions (멤버 경고·정지·강제 퇴장)
CREATE INDEX idx_member_sanc ON member_sanctions (blog_id, user_id, type);  -- 멤버별 정지 횟수·이력
CREATE INDEX idx_member_sanc_created ON member_sanctions (created_at);  -- 1년 지난 기록 삭제

-- owner_sanctions (블로그장 경고·권한 박탈)
CREATE INDEX idx_owner_sanc ON owner_sanctions (blog_id, user_id, type);  -- 경고 3번 세기
CREATE INDEX idx_owner_sanc_created ON owner_sanctions (created_at);  -- 1년 지난 기록 삭제

-- user_sanctions (계정 경고·프로필 초기화·정지)
CREATE INDEX idx_user_sanc ON user_sanctions (user_id, type);  -- 회원별 계정 제재 이력
CREATE INDEX idx_user_sanc_created ON user_sanctions (created_at);  -- 1년 지난 기록 삭제

-- blog_blacklist (블로그 블랙리스트)
CREATE INDEX idx_blacklist_email ON blog_blacklist (blog_id, email_hash);  -- 참여 신청 때 이메일 확인
CREATE INDEX idx_blacklist_phone ON blog_blacklist (blog_id, phone_hash);  -- 참여 신청 때 전화번호 확인

-- blacklist_inquiries (블랙리스트 해제 문의)
CREATE INDEX idx_inquiry_status ON blacklist_inquiries (blacklist_id, status);  -- 블로그장이 보는 대기 문의

-- reports (신고)
CREATE INDEX idx_reports_dup ON reports (reporter_id, target_type, target_id, created_at);  -- 같은 대상 2주 안 재신고 확인
CREATE INDEX idx_reports_blog ON reports (blog_id, status, created_at);  -- 블로그장이 보는 신고 목록
CREATE INDEX idx_reports_admin ON reports (handler_scope, status, created_at);  -- 관리자가 보는 신고 목록
CREATE INDEX idx_reports_target ON reports (target_type, target_id);  -- 대상별 신고 모아보기
CREATE INDEX idx_reports_created ON reports (created_at);  -- 1년 지난 기록 삭제

-- admin_action_logs (관리자 활동 기록)
CREATE INDEX idx_admin_logs_admin ON admin_action_logs (admin_id, created_at);  -- 관리자별 기록
CREATE INDEX idx_admin_logs_target ON admin_action_logs (target_type, target_id);  -- 대상별 기록
CREATE INDEX idx_admin_logs_created ON admin_action_logs (created_at);  -- 1년 지난 기록 삭제

-- posts (게시글)
CREATE INDEX idx_posts_blog ON posts (blog_id, status, created_at);  -- 블로그 글 목록 최신순 (BRD-02)
CREATE INDEX idx_posts_category ON posts (blog_id, category_id, status, created_at);  -- 카테고리별 목록
CREATE INDEX idx_posts_author ON posts (author_id, status, created_at);  -- 팔로우 피드, 내 글
CREATE INDEX idx_posts_feed ON posts (status, created_at);  -- 메인 피드 최신 글 (BRD-09)
CREATE INDEX idx_posts_popular ON posts (status, like_count);  -- 인기 글 = 좋아요 수 (BRD-09, D-89)
CREATE INDEX idx_posts_deleted ON posts (status, deleted_at);  -- 새벽 4시 배치: 삭제 30일 지난 글
CREATE FULLTEXT INDEX ft_posts_title ON posts (title) WITH PARSER ngram;  -- 글 제목 검색 (BRD-08), ngram

-- post_tags (글-태그 연결)
CREATE INDEX idx_post_tags_tag ON post_tags (tag_id, post_id);  -- 태그 눌러 글 찾기

-- post_images (글 이미지)
CREATE INDEX idx_post_images_post ON post_images (post_id, sort_order);  -- 글의 이미지 순서대로
CREATE INDEX idx_post_images_orphan ON post_images (post_id, created_at);  -- 글에 연결 안 된 이미지 정리

-- comments (댓글)
CREATE INDEX idx_comments_post ON comments (post_id, parent_id, created_at);  -- 글의 댓글·대댓글 순서대로
CREATE INDEX idx_comments_author ON comments (author_id, created_at);  -- 내 댓글

-- post_views (조회 기록)
CREATE INDEX idx_post_views_date ON post_views (view_date);  -- 오래된 조회 기록 정리

-- user_follows (회원 팔로우)
CREATE INDEX idx_follows_followee ON user_follows (followee_id, created_at);  -- 팔로워 목록

-- user_blocks (회원 차단)
CREATE INDEX idx_blocks_blocked ON user_blocks (blocked_id);  -- 나를 차단한 사람 확인

-- notifications (알림)
CREATE INDEX idx_notif_receiver ON notifications (receiver_id, tab, created_at);  -- 사이드바 탭별 목록
CREATE INDEX idx_notif_unread ON notifications (receiver_id, is_read);  -- 30초 폴링: 안 읽은 알림 수
CREATE INDEX idx_notif_created ON notifications (created_at);  -- 보관 기간 지난 알림 삭제

-- recent_searches (최근 검색어)
CREATE INDEX idx_recent_search ON recent_searches (user_id, searched_at);  -- 최근 10개
