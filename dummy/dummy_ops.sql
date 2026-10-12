-- 신고·참여 신청·제재·알림 더미 데이터 (화면 확인용)
-- dummy_data.sql, dummy_data_bulk.sql 다음에 넣습니다. 여러 번 넣어도 한 번만 들어갑니다.
--
-- 블로그장은 이메일이 아니라 blogs.owner_id로 찾습니다. 그래서 넣는 시점의 블로그장이 누구든
-- 그 사람이 블로그 관리 화면에서 처리할 신고와 참여 신청을 보게 됩니다.
--
-- 들어가는 것
--   1. 블로그장이 처리할 대기 신고(handler_scope = BLOG_OWNER): 블로그마다 글 2, 댓글 2, 회원 1
--   2. 블로그장이 이미 처리한 신고: 블로그마다 1건(문제 없음·경고·정지 번갈아), 그에 맞는 멤버 제재와 알림
--   3. 메인 관리자가 처리할 신고(handler_scope = ADMIN): 블로그장 본인·블로그 자체 신고, 메인 프로필 신고
--   4. 메인 관리자가 블로그장에게 준 경고 1건(가장 먼저 만든 블로그)과 알림
--   5. 승인제 블로그의 대기 참여 신청(블로그마다 3명까지)과 블로그장 알림
--   6. 가장 먼저 만든 블로그의 블로그장이 받을 댓글·대댓글·좋아요·팔로우·구독 알림
--      (그 블로그장이 쓴 글 3개, 거기 달린 댓글과 좋아요, 팔로워 12명을 함께 넣습니다)
--   블랙리스트는 이름·이메일·전화번호 해시에 서버 비밀값이 필요해 넣지 않습니다.

SET NAMES utf8mb4;

SET @seeded := (SELECT COUNT(*) FROM reports WHERE detail = '[예시] 더미 신고 데이터 표시');
SET @admin := (SELECT id FROM users WHERE email = 'admin@example.com');

-- 아래 INSERT마다 같은 WITH 절로 고릅니다(서버 DB 계정에 임시 테이블 권한이 없음).
--   tmp_members: 블로그마다 블로그장이 아닌 멤버(정지 안 됨) 순번
--   tmp_posts: 블로그장이 아닌 사람이 쓴 글(블로그마다 최근 3개)
--   tmp_comments: 블로그장이 아닌 사람이 쓴 댓글(블로그마다 최근 2개)

-- 신고자: 대상 작성자가 아닌 그 블로그 멤버 중 순번이 가장 앞선 사람
-- 1. 대기 신고: 글 (최근 글 1, 2번째)
INSERT INTO reports (reporter_id, target_type, target_id, blog_id, handler_scope, reason, detail, target_snapshot, status, created_at)
WITH tmp_members AS (
  SELECT m.blog_id, m.user_id,
         ROW_NUMBER() OVER (PARTITION BY m.blog_id ORDER BY m.joined_at, m.user_id) AS rn
  FROM blog_members m
  JOIN blogs b ON b.id = m.blog_id
  JOIN users u ON u.id = m.user_id
  WHERE b.status = 'ACTIVE' AND m.user_id <> b.owner_id AND m.suspended_until IS NULL
    AND u.status = 'ACTIVE' AND u.role = 'USER'
), tmp_posts AS (
  SELECT * FROM (
    SELECT p.id, p.blog_id, p.author_id, p.title, p.content,
           ROW_NUMBER() OVER (PARTITION BY p.blog_id ORDER BY p.created_at DESC, p.id DESC) AS rn
    FROM posts p
    JOIN blogs b ON b.id = p.blog_id
    JOIN tmp_members tm ON tm.blog_id = p.blog_id AND tm.user_id = p.author_id
    WHERE p.status = 'PUBLISHED' AND b.status = 'ACTIVE'
  ) x WHERE rn <= 3
), tmp_comments AS (
  SELECT * FROM (
    SELECT c.id, p.blog_id, c.author_id, u.nickname, c.content,
           ROW_NUMBER() OVER (PARTITION BY p.blog_id ORDER BY c.created_at DESC, c.id DESC) AS rn
    FROM comments c
    JOIN posts p ON p.id = c.post_id
    JOIN users u ON u.id = c.author_id
    JOIN tmp_members tm ON tm.blog_id = p.blog_id AND tm.user_id = c.author_id
    WHERE c.status = 'ACTIVE' AND p.status = 'PUBLISHED'
  ) x WHERE rn <= 2
)
SELECT r.user_id, 'POST', p.id, p.blog_id, 'BLOG_OWNER',
       ELT(1 + (p.id % 5), 'SPAM', 'ABUSE', 'ADULT', 'ILLEGAL', 'ETC'),
       ELT(1 + (p.id % 4), '같은 홍보 글을 계속 올려요.', '제목과 다른 내용이에요. 확인 부탁드려요.', NULL, '다른 블로그 글을 그대로 가져온 것 같아요.'),
       CONCAT('[글] ', p.title, '\n', LEFT(REPLACE(p.content, '\n', ' '), 200)),
       'PENDING', NOW() - INTERVAL (p.id % 72) HOUR
FROM tmp_posts p
JOIN tmp_members r ON r.blog_id = p.blog_id
 AND r.rn = (SELECT MIN(r2.rn) FROM tmp_members r2 WHERE r2.blog_id = p.blog_id AND r2.user_id <> p.author_id)
WHERE @seeded = 0 AND p.rn <= 2;

-- 1. 대기 신고: 댓글
INSERT INTO reports (reporter_id, target_type, target_id, blog_id, handler_scope, reason, detail, target_snapshot, status, created_at)
WITH tmp_members AS (
  SELECT m.blog_id, m.user_id,
         ROW_NUMBER() OVER (PARTITION BY m.blog_id ORDER BY m.joined_at, m.user_id) AS rn
  FROM blog_members m
  JOIN blogs b ON b.id = m.blog_id
  JOIN users u ON u.id = m.user_id
  WHERE b.status = 'ACTIVE' AND m.user_id <> b.owner_id AND m.suspended_until IS NULL
    AND u.status = 'ACTIVE' AND u.role = 'USER'
), tmp_posts AS (
  SELECT * FROM (
    SELECT p.id, p.blog_id, p.author_id, p.title, p.content,
           ROW_NUMBER() OVER (PARTITION BY p.blog_id ORDER BY p.created_at DESC, p.id DESC) AS rn
    FROM posts p
    JOIN blogs b ON b.id = p.blog_id
    JOIN tmp_members tm ON tm.blog_id = p.blog_id AND tm.user_id = p.author_id
    WHERE p.status = 'PUBLISHED' AND b.status = 'ACTIVE'
  ) x WHERE rn <= 3
), tmp_comments AS (
  SELECT * FROM (
    SELECT c.id, p.blog_id, c.author_id, u.nickname, c.content,
           ROW_NUMBER() OVER (PARTITION BY p.blog_id ORDER BY c.created_at DESC, c.id DESC) AS rn
    FROM comments c
    JOIN posts p ON p.id = c.post_id
    JOIN users u ON u.id = c.author_id
    JOIN tmp_members tm ON tm.blog_id = p.blog_id AND tm.user_id = c.author_id
    WHERE c.status = 'ACTIVE' AND p.status = 'PUBLISHED'
  ) x WHERE rn <= 2
)
SELECT r.user_id, 'COMMENT', c.id, c.blog_id, 'BLOG_OWNER',
       ELT(1 + (c.id % 3), 'ABUSE', 'SPAM', 'ETC'),
       ELT(1 + (c.id % 3), '말투가 너무 공격적이에요.', NULL, '댓글마다 같은 링크를 달아요.'),
       CONCAT('[댓글] ', COALESCE(c.nickname, '탈퇴한 계정'), ': ', c.content),
       'PENDING', NOW() - INTERVAL (c.id % 48) HOUR
FROM tmp_comments c
JOIN tmp_members r ON r.blog_id = c.blog_id
 AND r.rn = (SELECT MIN(r2.rn) FROM tmp_members r2 WHERE r2.blog_id = c.blog_id AND r2.user_id <> c.author_id)
WHERE @seeded = 0;

-- 1. 대기 신고: 블로그 안 회원 (순번 마지막 멤버를 첫 멤버가 신고)
INSERT INTO reports (reporter_id, target_type, target_id, blog_id, handler_scope, reason, detail, target_snapshot, status, created_at)
WITH tmp_members AS (
  SELECT m.blog_id, m.user_id,
         ROW_NUMBER() OVER (PARTITION BY m.blog_id ORDER BY m.joined_at, m.user_id) AS rn
  FROM blog_members m
  JOIN blogs b ON b.id = m.blog_id
  JOIN users u ON u.id = m.user_id
  WHERE b.status = 'ACTIVE' AND m.user_id <> b.owner_id AND m.suspended_until IS NULL
    AND u.status = 'ACTIVE' AND u.role = 'USER'
), tmp_posts AS (
  SELECT * FROM (
    SELECT p.id, p.blog_id, p.author_id, p.title, p.content,
           ROW_NUMBER() OVER (PARTITION BY p.blog_id ORDER BY p.created_at DESC, p.id DESC) AS rn
    FROM posts p
    JOIN blogs b ON b.id = p.blog_id
    JOIN tmp_members tm ON tm.blog_id = p.blog_id AND tm.user_id = p.author_id
    WHERE p.status = 'PUBLISHED' AND b.status = 'ACTIVE'
  ) x WHERE rn <= 3
), tmp_comments AS (
  SELECT * FROM (
    SELECT c.id, p.blog_id, c.author_id, u.nickname, c.content,
           ROW_NUMBER() OVER (PARTITION BY p.blog_id ORDER BY c.created_at DESC, c.id DESC) AS rn
    FROM comments c
    JOIN posts p ON p.id = c.post_id
    JOIN users u ON u.id = c.author_id
    JOIN tmp_members tm ON tm.blog_id = p.blog_id AND tm.user_id = c.author_id
    WHERE c.status = 'ACTIVE' AND p.status = 'PUBLISHED'
  ) x WHERE rn <= 2
)
SELECT r.user_id, 'USER', t.user_id, t.blog_id, 'BLOG_OWNER', 'ABUSE',
       '여러 글에 같은 비방 댓글을 달고 있어요.',
       CONCAT('[회원] ', u.nickname), 'PENDING', NOW() - INTERVAL 5 HOUR
FROM tmp_members t
JOIN users u ON u.id = t.user_id
JOIN tmp_members r ON r.blog_id = t.blog_id AND r.rn = 1
WHERE @seeded = 0 AND t.rn > 1
  AND t.rn = (SELECT MAX(x.rn) FROM tmp_members x WHERE x.blog_id = t.blog_id);

-- 2. 이미 처리한 신고: 블로그마다 최근 3번째 글. 블로그 번호에 따라 문제 없음 / 경고 / 3일 정지
INSERT INTO reports (reporter_id, target_type, target_id, blog_id, handler_scope, reason, detail, target_snapshot, status, handled_by, handled_at, created_at)
WITH tmp_members AS (
  SELECT m.blog_id, m.user_id,
         ROW_NUMBER() OVER (PARTITION BY m.blog_id ORDER BY m.joined_at, m.user_id) AS rn
  FROM blog_members m
  JOIN blogs b ON b.id = m.blog_id
  JOIN users u ON u.id = m.user_id
  WHERE b.status = 'ACTIVE' AND m.user_id <> b.owner_id AND m.suspended_until IS NULL
    AND u.status = 'ACTIVE' AND u.role = 'USER'
), tmp_posts AS (
  SELECT * FROM (
    SELECT p.id, p.blog_id, p.author_id, p.title, p.content,
           ROW_NUMBER() OVER (PARTITION BY p.blog_id ORDER BY p.created_at DESC, p.id DESC) AS rn
    FROM posts p
    JOIN blogs b ON b.id = p.blog_id
    JOIN tmp_members tm ON tm.blog_id = p.blog_id AND tm.user_id = p.author_id
    WHERE p.status = 'PUBLISHED' AND b.status = 'ACTIVE'
  ) x WHERE rn <= 3
), tmp_comments AS (
  SELECT * FROM (
    SELECT c.id, p.blog_id, c.author_id, u.nickname, c.content,
           ROW_NUMBER() OVER (PARTITION BY p.blog_id ORDER BY c.created_at DESC, c.id DESC) AS rn
    FROM comments c
    JOIN posts p ON p.id = c.post_id
    JOIN users u ON u.id = c.author_id
    JOIN tmp_members tm ON tm.blog_id = p.blog_id AND tm.user_id = c.author_id
    WHERE c.status = 'ACTIVE' AND p.status = 'PUBLISHED'
  ) x WHERE rn <= 2
)
SELECT r.user_id, 'POST', p.id, p.blog_id, 'BLOG_OWNER', 'SPAM', '광고 글이에요.',
       CONCAT('[글] ', p.title, '\n', LEFT(REPLACE(p.content, '\n', ' '), 200)),
       ELT(1 + (p.blog_id % 3), 'NO_ISSUE', 'WARNED', 'SUSPENDED'),
       b.owner_id, NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 3 DAY
FROM tmp_posts p
JOIN blogs b ON b.id = p.blog_id
JOIN tmp_members r ON r.blog_id = p.blog_id
 AND r.rn = (SELECT MIN(r2.rn) FROM tmp_members r2 WHERE r2.blog_id = p.blog_id AND r2.user_id <> p.author_id)
WHERE @seeded = 0 AND p.rn = 3;

-- 2. 처리한 신고에 맞는 멤버 제재 (경고, 3일 정지)
INSERT INTO member_sanctions (blog_id, user_id, type, suspend_days, ends_at, reason, report_id, issued_by, created_at)
SELECT rp.blog_id, p.author_id,
       IF(rp.status = 'WARNED', 'WARNING', 'SUSPENSION'),
       IF(rp.status = 'WARNED', NULL, 3),
       IF(rp.status = 'WARNED', NULL, rp.handled_at + INTERVAL 3 DAY),
       IF(rp.status = 'WARNED', '광고 글을 반복해서 올렸어요.', '경고 뒤에도 광고 글을 올렸어요.'),
       rp.id, rp.handled_by, rp.handled_at
FROM reports rp
JOIN posts p ON p.id = rp.target_id
WHERE @seeded = 0 AND rp.target_type = 'POST' AND rp.detail = '광고 글이에요.'
  AND rp.status IN ('WARNED', 'SUSPENDED');

UPDATE blog_members m
JOIN member_sanctions s ON s.blog_id = m.blog_id AND s.user_id = m.user_id
SET m.suspended_until = s.ends_at, m.suspension_count = m.suspension_count + 1
WHERE @seeded = 0 AND s.type = 'SUSPENSION' AND s.reason = '경고 뒤에도 광고 글을 올렸어요.';

-- 2. 알림: 신고자에게 처리 결과, 대상자에게 제재
INSERT INTO notifications (receiver_id, actor_id, tab, type, target_type, target_id, message, is_read, created_at)
SELECT rp.reporter_id, rp.handled_by, 'OPERATION', 'REPORT_RESULT', 'REPORT', rp.id,
       CONCAT('신고하신 내용이 처리되었어요: ', ELT(FIELD(rp.status, 'NO_ISSUE', 'WARNED', 'SUSPENDED'), '문제 없음', '경고', '정지')),
       FALSE, rp.handled_at
FROM reports rp
WHERE @seeded = 0 AND rp.detail = '광고 글이에요.' AND rp.status <> 'PENDING';

INSERT INTO notifications (receiver_id, actor_id, tab, type, target_type, target_id, message, is_read, created_at)
SELECT s.user_id, s.issued_by, 'OPERATION', 'MEMBER_SANCTION', 'BLOG', s.blog_id,
       CONCAT('[', b.name, '] ', IF(s.type = 'WARNING', '경고를 받았어요: ', '3일 동안 활동이 정지되었어요: '), s.reason),
       FALSE, s.created_at
FROM member_sanctions s
JOIN blogs b ON b.id = s.blog_id
WHERE @seeded = 0 AND s.reason IN ('광고 글을 반복해서 올렸어요.', '경고 뒤에도 광고 글을 올렸어요.');

-- 3. 메인 관리자가 처리할 신고: 블로그장 본인 (블로그마다), 블로그 자체 (블로그 번호 짝수)
INSERT INTO reports (reporter_id, target_type, target_id, blog_id, handler_scope, reason, detail, target_snapshot, status, created_at)
WITH tmp_members AS (
  SELECT m.blog_id, m.user_id,
         ROW_NUMBER() OVER (PARTITION BY m.blog_id ORDER BY m.joined_at, m.user_id) AS rn
  FROM blog_members m
  JOIN blogs b ON b.id = m.blog_id
  JOIN users u ON u.id = m.user_id
  WHERE b.status = 'ACTIVE' AND m.user_id <> b.owner_id AND m.suspended_until IS NULL
    AND u.status = 'ACTIVE' AND u.role = 'USER'
), tmp_posts AS (
  SELECT * FROM (
    SELECT p.id, p.blog_id, p.author_id, p.title, p.content,
           ROW_NUMBER() OVER (PARTITION BY p.blog_id ORDER BY p.created_at DESC, p.id DESC) AS rn
    FROM posts p
    JOIN blogs b ON b.id = p.blog_id
    JOIN tmp_members tm ON tm.blog_id = p.blog_id AND tm.user_id = p.author_id
    WHERE p.status = 'PUBLISHED' AND b.status = 'ACTIVE'
  ) x WHERE rn <= 3
), tmp_comments AS (
  SELECT * FROM (
    SELECT c.id, p.blog_id, c.author_id, u.nickname, c.content,
           ROW_NUMBER() OVER (PARTITION BY p.blog_id ORDER BY c.created_at DESC, c.id DESC) AS rn
    FROM comments c
    JOIN posts p ON p.id = c.post_id
    JOIN users u ON u.id = c.author_id
    JOIN tmp_members tm ON tm.blog_id = p.blog_id AND tm.user_id = c.author_id
    WHERE c.status = 'ACTIVE' AND p.status = 'PUBLISHED'
  ) x WHERE rn <= 2
)
SELECT r.user_id, 'USER', b.owner_id, b.id, 'ADMIN', 'ABUSE',
       '블로그장이 멤버 댓글을 이유 없이 지워요.',
       CONCAT('[회원] ', u.nickname), 'PENDING', NOW() - INTERVAL 10 HOUR
FROM blogs b
JOIN users u ON u.id = b.owner_id
JOIN tmp_members r ON r.blog_id = b.id AND r.rn = 1
WHERE @seeded = 0 AND b.status = 'ACTIVE';

INSERT INTO reports (reporter_id, target_type, target_id, blog_id, handler_scope, reason, detail, target_snapshot, status, created_at)
WITH tmp_members AS (
  SELECT m.blog_id, m.user_id,
         ROW_NUMBER() OVER (PARTITION BY m.blog_id ORDER BY m.joined_at, m.user_id) AS rn
  FROM blog_members m
  JOIN blogs b ON b.id = m.blog_id
  JOIN users u ON u.id = m.user_id
  WHERE b.status = 'ACTIVE' AND m.user_id <> b.owner_id AND m.suspended_until IS NULL
    AND u.status = 'ACTIVE' AND u.role = 'USER'
), tmp_posts AS (
  SELECT * FROM (
    SELECT p.id, p.blog_id, p.author_id, p.title, p.content,
           ROW_NUMBER() OVER (PARTITION BY p.blog_id ORDER BY p.created_at DESC, p.id DESC) AS rn
    FROM posts p
    JOIN blogs b ON b.id = p.blog_id
    JOIN tmp_members tm ON tm.blog_id = p.blog_id AND tm.user_id = p.author_id
    WHERE p.status = 'PUBLISHED' AND b.status = 'ACTIVE'
  ) x WHERE rn <= 3
), tmp_comments AS (
  SELECT * FROM (
    SELECT c.id, p.blog_id, c.author_id, u.nickname, c.content,
           ROW_NUMBER() OVER (PARTITION BY p.blog_id ORDER BY c.created_at DESC, c.id DESC) AS rn
    FROM comments c
    JOIN posts p ON p.id = c.post_id
    JOIN users u ON u.id = c.author_id
    JOIN tmp_members tm ON tm.blog_id = p.blog_id AND tm.user_id = c.author_id
    WHERE c.status = 'ACTIVE' AND p.status = 'PUBLISHED'
  ) x WHERE rn <= 2
)
SELECT r.user_id, 'BLOG', b.id, b.id, 'ADMIN', 'ETC',
       '블로그 소개와 실제 글 주제가 달라요.',
       CONCAT('[블로그] ', b.name, IF(b.description IS NULL, '', CONCAT('\n', b.description))),
       'PENDING', NOW() - INTERVAL 20 HOUR
FROM blogs b
JOIN tmp_members r ON r.blog_id = b.id AND r.rn = 2
WHERE @seeded = 0 AND b.status = 'ACTIVE' AND b.id % 2 = 0;

-- 3. 메인 프로필 신고 (블로그 없이): 대량 회원 몇 명
INSERT INTO reports (reporter_id, target_type, target_id, blog_id, handler_scope, reason, detail, target_snapshot, status, created_at)
SELECT rep.id, 'USER', t.id, NULL, 'ADMIN', 'ADULT', '프로필 사진이 부적절해요.',
       CONCAT('[회원] ', t.nickname), 'PENDING', NOW() - INTERVAL 2 DAY
FROM users t
JOIN users rep ON rep.email = 'bulk001@example.com'
WHERE @seeded = 0 AND t.email IN ('bulk010@example.com', 'bulk020@example.com', 'bulk030@example.com');

-- 4. 메인 관리자가 블로그장에게 준 경고 (가장 먼저 만든 블로그)
SET @first_blog := (SELECT id FROM blogs WHERE status = 'ACTIVE' ORDER BY created_at, id LIMIT 1);

INSERT INTO reports (reporter_id, target_type, target_id, blog_id, handler_scope, reason, detail, target_snapshot, status, handled_by, handled_at, created_at)
WITH tmp_members AS (
  SELECT m.blog_id, m.user_id,
         ROW_NUMBER() OVER (PARTITION BY m.blog_id ORDER BY m.joined_at, m.user_id) AS rn
  FROM blog_members m
  JOIN blogs b ON b.id = m.blog_id
  JOIN users u ON u.id = m.user_id
  WHERE b.status = 'ACTIVE' AND m.user_id <> b.owner_id AND m.suspended_until IS NULL
    AND u.status = 'ACTIVE' AND u.role = 'USER'
), tmp_posts AS (
  SELECT * FROM (
    SELECT p.id, p.blog_id, p.author_id, p.title, p.content,
           ROW_NUMBER() OVER (PARTITION BY p.blog_id ORDER BY p.created_at DESC, p.id DESC) AS rn
    FROM posts p
    JOIN blogs b ON b.id = p.blog_id
    JOIN tmp_members tm ON tm.blog_id = p.blog_id AND tm.user_id = p.author_id
    WHERE p.status = 'PUBLISHED' AND b.status = 'ACTIVE'
  ) x WHERE rn <= 3
), tmp_comments AS (
  SELECT * FROM (
    SELECT c.id, p.blog_id, c.author_id, u.nickname, c.content,
           ROW_NUMBER() OVER (PARTITION BY p.blog_id ORDER BY c.created_at DESC, c.id DESC) AS rn
    FROM comments c
    JOIN posts p ON p.id = c.post_id
    JOIN users u ON u.id = c.author_id
    JOIN tmp_members tm ON tm.blog_id = p.blog_id AND tm.user_id = c.author_id
    WHERE c.status = 'ACTIVE' AND p.status = 'PUBLISHED'
  ) x WHERE rn <= 2
)
SELECT r.user_id, 'USER', b.owner_id, b.id, 'ADMIN', 'ABUSE', '공지 댓글에서 멤버에게 욕설을 했어요.',
       CONCAT('[회원] ', u.nickname), 'WARNED', @admin, NOW() - INTERVAL 2 DAY, NOW() - INTERVAL 4 DAY
FROM blogs b
JOIN users u ON u.id = b.owner_id
JOIN tmp_members r ON r.blog_id = b.id AND r.rn = 2
WHERE @seeded = 0 AND b.id = @first_blog AND @admin IS NOT NULL;

INSERT INTO owner_sanctions (blog_id, user_id, type, reason, report_id, admin_id, created_at)
SELECT rp.blog_id, rp.target_id, 'WARNING', '멤버에게 욕설을 했어요. 한 번 더 받으면 경고 2회입니다.', rp.id, @admin, rp.handled_at
FROM reports rp
WHERE @seeded = 0 AND rp.detail = '공지 댓글에서 멤버에게 욕설을 했어요.';

INSERT INTO notifications (receiver_id, actor_id, tab, type, target_type, target_id, message, is_read, created_at)
SELECT s.user_id, NULL, 'OPERATION', 'OWNER_SANCTION', 'BLOG', s.blog_id,
       CONCAT('[', b.name, '] 블로그장 경고를 받았어요: ', s.reason), FALSE, s.created_at
FROM owner_sanctions s
JOIN blogs b ON b.id = s.blog_id
WHERE @seeded = 0 AND s.reason LIKE '멤버에게 욕설을 했어요.%';

INSERT INTO notifications (receiver_id, actor_id, tab, type, target_type, target_id, message, is_read, created_at)
SELECT rp.reporter_id, NULL, 'OPERATION', 'REPORT_RESULT', 'REPORT', rp.id,
       '신고하신 내용이 처리되었어요: 블로그장 경고', FALSE, rp.handled_at
FROM reports rp
WHERE @seeded = 0 AND rp.detail = '공지 댓글에서 멤버에게 욕설을 했어요.';

-- 5. 승인제 블로그의 대기 참여 신청: 멤버가 아니고 대기 신청도 없는 대량 회원 중 3명
INSERT INTO blog_join_requests (blog_id, user_id, status, created_at)
SELECT blog_id, user_id, 'PENDING', NOW() - INTERVAL (rn * 7) HOUR
FROM (
  SELECT b.id AS blog_id, u.id AS user_id,
         ROW_NUMBER() OVER (PARTITION BY b.id ORDER BY (u.id * 7 + b.id * 13) % 101, u.id) AS rn
  FROM blogs b
  JOIN users u ON u.email LIKE 'bulk%@example.com' AND u.status = 'ACTIVE'
  WHERE b.status = 'ACTIVE' AND b.join_policy = 'APPROVAL'
    AND NOT EXISTS (SELECT 1 FROM blog_members m WHERE m.blog_id = b.id AND m.user_id = u.id)
    AND NOT EXISTS (SELECT 1 FROM blog_join_requests j WHERE j.blog_id = b.id AND j.user_id = u.id)
) x
WHERE @seeded = 0 AND rn <= 3;

INSERT INTO notifications (receiver_id, actor_id, tab, type, target_type, target_id, message, is_read, created_at)
SELECT b.owner_id, j.user_id, 'BLOG', 'JOIN_REQUEST', 'BLOG', b.id,
       CONCAT(u.nickname, '님이 [', b.name, ']에 참여를 신청했어요.'), FALSE, j.created_at
FROM blog_join_requests j
JOIN blogs b ON b.id = j.blog_id
JOIN users u ON u.id = j.user_id
WHERE @seeded = 0 AND j.status = 'PENDING'
  AND NOT EXISTS (SELECT 1 FROM notifications n WHERE n.type = 'JOIN_REQUEST' AND n.actor_id = j.user_id AND n.target_id = b.id);

-- 다시 넣지 않게 표시 (메인 관리자 화면에 보이는 메인 프로필 신고 1건)
INSERT INTO reports (reporter_id, target_type, target_id, blog_id, handler_scope, reason, detail, target_snapshot, status, created_at)
SELECT rep.id, 'USER', t.id, NULL, 'ADMIN', 'ETC', '[예시] 더미 신고 데이터 표시',
       CONCAT('[회원] ', t.nickname), 'NO_ISSUE', NOW() - INTERVAL 7 DAY
FROM users t
JOIN users rep ON rep.email = 'bulk002@example.com'
WHERE @seeded = 0 AND t.email = 'bulk003@example.com';

-- 6. 가장 먼저 만든 블로그의 블로그장이 받을 댓글·대댓글·좋아요·팔로우·구독 알림
--    1~5와 따로 한 번만 들어갑니다(블로그장이 쓴 첫 글 제목으로 확인).
SET @owner := (SELECT owner_id FROM blogs WHERE id = @first_blog);
SET @seeded6 := (SELECT COUNT(*) FROM posts WHERE blog_id = @first_blog AND author_id = @owner AND title = '반가워요, 블로그장입니다');

INSERT INTO posts (blog_id, author_id, title, content, is_notice, created_at)
SELECT @first_blog, @owner, t.title, t.content, t.notice, NOW() - INTERVAL t.h HOUR
FROM (
  SELECT '반가워요, 블로그장입니다' AS title, '블로그를 새로 맡게 됐어요.\n\n앞으로 글도 자주 올리고 댓글도 열심히 달게요. 잘 부탁드려요!' AS content, TRUE AS notice, 50 AS h
  UNION ALL SELECT '이번 주 추천 레시피 모음', '멤버분들이 올려 주신 글 중에 반응이 좋았던 것만 골라 봤어요.\n\n- 제육볶음\n- 크림 파스타\n- 군고구마', FALSE, 30
  UNION ALL SELECT '댓글 이벤트 합니다', '이 글에 가장 해 보고 싶은 요리를 댓글로 남겨 주세요.\n\n추첨해서 다음 글 주제로 정할게요.', FALSE, 8
) t
WHERE @seeded6 = 0 AND @owner IS NOT NULL;

-- 블로그장 글에 멤버 댓글 (글마다 멤버 앞 4명)
INSERT INTO comments (post_id, author_id, content, created_at)
WITH m AS (
  SELECT bm.user_id, ROW_NUMBER() OVER (ORDER BY bm.joined_at, bm.user_id) AS rn
  FROM blog_members bm JOIN users u ON u.id = bm.user_id
  WHERE bm.blog_id = @first_blog AND bm.user_id <> @owner AND bm.suspended_until IS NULL
    AND u.status = 'ACTIVE' AND u.role = 'USER'
)
SELECT p.id, m.user_id,
       ELT(m.rn, '환영해요! 앞으로 자주 들를게요.', '좋은 글 감사합니다 :)', '저는 김치찌개요!', '다음 글도 기대할게요.'),
       p.created_at + INTERVAL (m.rn * 2) HOUR
FROM posts p JOIN m ON m.rn <= 4
WHERE @seeded6 = 0 AND p.blog_id = @first_blog AND p.author_id = @owner
  AND p.title IN ('반가워요, 블로그장입니다', '이번 주 추천 레시피 모음', '댓글 이벤트 합니다');

INSERT INTO notifications (receiver_id, actor_id, tab, type, target_type, target_id, message, is_read, created_at)
SELECT @owner, c.author_id, 'COMMENT', 'COMMENT', 'POST', p.id,
       CONCAT(u.nickname, '님이 내 글 ''', p.title, '''에 댓글을 남겼어요.'), FALSE, c.created_at
FROM comments c JOIN posts p ON p.id = c.post_id JOIN users u ON u.id = c.author_id
WHERE @seeded6 = 0 AND p.blog_id = @first_blog AND p.author_id = @owner AND c.parent_id IS NULL
  AND p.title IN ('반가워요, 블로그장입니다', '이번 주 추천 레시피 모음', '댓글 이벤트 합니다');

-- 블로그장이 멤버 글에 단 댓글과, 그 댓글에 달린 대댓글 (멤버 글 2개)
INSERT INTO comments (post_id, author_id, content, created_at)
SELECT p.id, @owner, '정성 가득한 글이네요. 공지에 소개해도 될까요?', NOW() - INTERVAL 20 HOUR
FROM posts p
WHERE @seeded6 = 0 AND p.blog_id = @first_blog AND p.author_id <> @owner AND p.status = 'PUBLISHED'
ORDER BY p.created_at DESC, p.id DESC LIMIT 2;

INSERT INTO comments (post_id, author_id, parent_id, reply_to_user_id, content, created_at)
SELECT c.post_id, p.author_id, c.id, @owner, '물론이죠! 감사합니다 :)', c.created_at + INTERVAL 3 HOUR
FROM comments c JOIN posts p ON p.id = c.post_id
WHERE @seeded6 = 0 AND c.author_id = @owner AND c.content = '정성 가득한 글이네요. 공지에 소개해도 될까요?';

INSERT INTO notifications (receiver_id, actor_id, tab, type, target_type, target_id, message, is_read, created_at)
SELECT @owner, r.author_id, 'COMMENT', 'REPLY', 'POST', r.post_id,
       CONCAT(u.nickname, '님이 내 댓글에 답글을 남겼어요.'), FALSE, r.created_at
FROM comments r JOIN users u ON u.id = r.author_id
WHERE @seeded6 = 0 AND r.reply_to_user_id = @owner AND r.content = '물론이죠! 감사합니다 :)';

-- 블로그장 글에 좋아요 (글마다 멤버 앞 6명)
INSERT IGNORE INTO post_likes (post_id, user_id, created_at)
WITH m AS (
  SELECT bm.user_id, ROW_NUMBER() OVER (ORDER BY bm.joined_at DESC, bm.user_id) AS rn
  FROM blog_members bm JOIN users u ON u.id = bm.user_id
  WHERE bm.blog_id = @first_blog AND bm.user_id <> @owner AND u.status = 'ACTIVE' AND u.role = 'USER'
)
SELECT p.id, m.user_id, p.created_at + INTERVAL m.rn HOUR
FROM posts p JOIN m ON m.rn <= 6
WHERE @seeded6 = 0 AND p.blog_id = @first_blog AND p.author_id = @owner
  AND p.title IN ('반가워요, 블로그장입니다', '이번 주 추천 레시피 모음', '댓글 이벤트 합니다');

INSERT INTO notifications (receiver_id, actor_id, tab, type, target_type, target_id, message, is_read, created_at)
SELECT @owner, l.user_id, 'LIKE', 'POST_LIKE', 'POST', p.id,
       CONCAT(u.nickname, '님이 내 글 ''', p.title, '''을(를) 좋아해요.'), FALSE, l.created_at
FROM post_likes l JOIN posts p ON p.id = l.post_id JOIN users u ON u.id = l.user_id
WHERE @seeded6 = 0 AND p.blog_id = @first_blog AND p.author_id = @owner
  AND p.title IN ('반가워요, 블로그장입니다', '이번 주 추천 레시피 모음', '댓글 이벤트 합니다');

-- 글·블로그 숫자 맞추기
UPDATE posts p
SET p.like_count = (SELECT COUNT(*) FROM post_likes l WHERE l.post_id = p.id),
    p.comment_count = (SELECT COUNT(*) FROM comments c WHERE c.post_id = p.id AND c.status = 'ACTIVE')
WHERE @seeded6 = 0 AND p.blog_id = @first_blog;

UPDATE blogs b
SET b.post_count = (SELECT COUNT(*) FROM posts p WHERE p.blog_id = b.id AND p.status = 'PUBLISHED')
WHERE @seeded6 = 0 AND b.id = @first_blog;

-- 팔로워 12명 (대량 회원 bulk040~bulk051)
INSERT IGNORE INTO user_follows (follower_id, followee_id, created_at)
SELECT u.id, @owner, NOW() - INTERVAL (CAST(SUBSTRING(u.email, 5, 3) AS UNSIGNED) - 39) * 3 HOUR
FROM users u
WHERE @seeded6 = 0 AND @owner IS NOT NULL AND u.status = 'ACTIVE' AND u.id <> @owner
  AND u.email BETWEEN 'bulk040@example.com' AND 'bulk051@example.com';

INSERT INTO notifications (receiver_id, actor_id, tab, type, target_type, target_id, message, is_read, created_at)
SELECT @owner, f.follower_id, 'FOLLOW', 'FOLLOW', 'USER', f.follower_id,
       CONCAT(u.nickname, '님이 나를 팔로우해요.'), FALSE, f.created_at
FROM user_follows f JOIN users u ON u.id = f.follower_id
WHERE @seeded6 = 0 AND f.followee_id = @owner AND u.email BETWEEN 'bulk040@example.com' AND 'bulk051@example.com';

-- 구독 알림: 이 블로그장이 가진 블로그의 최근 구독 15건
INSERT INTO notifications (receiver_id, actor_id, tab, type, target_type, target_id, message, is_read, created_at)
SELECT @owner, s.user_id, 'FOLLOW', 'BLOG_SUBSCRIBE', 'USER', s.user_id,
       CONCAT(u.nickname, '님이 ''', b.name, ''' 블로그를 구독해요.'), FALSE, s.created_at
FROM blog_subscriptions s JOIN blogs b ON b.id = s.blog_id JOIN users u ON u.id = s.user_id
WHERE @seeded6 = 0 AND b.owner_id = @owner AND s.user_id <> @owner
ORDER BY s.created_at DESC, s.id DESC LIMIT 15;

SELECT handler_scope, status, COUNT(*) AS reports FROM reports GROUP BY handler_scope, status ORDER BY 1, 2;
