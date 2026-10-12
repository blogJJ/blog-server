"""dummy_data_bulk.sql을 만든다: python3 dummy/make_bulk.py > dummy/dummy_data_bulk.sql

dummy_data.sql(회원 20명, 블로그 8개) 위에 더 많은 데이터를 얹는다.
같은 시드로 돌리면 항상 같은 파일이 나오고, 여러 번 넣어도 같은 행이 두 번 생기지 않는다.
"""

import os
import random
import re

random.seed(20261011)

# dummy_data.sql 회원의 닉네임과 겹치면 INSERT IGNORE가 그 회원을 통째로 건너뛰므로 피한다
_base = open(os.path.join(os.path.dirname(__file__), "dummy_data.sql"), encoding="utf-8").read()
TAKEN_NICKS = set(re.findall(r"^  \((?:'[^']*'|NULL), '[^']*', '[^']*', '([^']*)'", _base, re.M))

# test1234!
PW = "$2a$10$P0o/rCVMYrGtButZsGDwy.4LtZ3.FCYts9JsZZ4MP.GkyAjH839Rm"

N_USERS = 120
LAST = "김이박최정강조윤장임한오서신권황안송류전홍고문양손배백허유남심노하곽성차주우구민진지엄채원천방공현함변염여추도소석선설마길연위표명기반왕금옥육인맹제모탁국어은편용"
FIRST = [
    "민준", "서연", "도윤", "지우", "하준", "서윤", "예준", "지호", "수아", "시우",
    "지안", "주원", "하은", "건우", "유나", "준서", "채원", "현우", "다은", "은우",
    "지민", "수빈", "태윤", "예린", "승민", "나연", "우진", "소율", "재윤", "가은",
    "민서", "도현", "아린", "준호", "하린", "시윤", "윤서", "지훈", "서아", "로운",
]
NICK_TAIL = ["", "의하루", "로그", "노트", "다이어리", "기록", "일상", "공방", "연구소", "책방"]

# 주제별 블로그 재료: (slug 앞부분, 이름 뒷부분, 소개, 카테고리, 태그, 글 제목, 본문 조각, 댓글)
TOPICS = [
    ("cook", "의 부엌", "매일 해 먹는 집밥 기록", ["한식", "양식", "간식"], ["요리", "레시피", "집밥"],
     ["김치볶음밥 황금 레시피", "10분 계란말이", "초간단 떡볶이", "냉장고 털이 파스타", "주말 브런치",
      "닭가슴살 샐러드", "엄마표 미역국", "에어프라이어 감자", "비빔국수 양념장", "어묵탕 끓이기",
      "두부조림 비법", "콩나물국 시원하게", "카레 맛있게 만들기", "잡채 쉽게 하기", "오므라이스 도전"],
     ["재료는 마트에서 쉽게 구할 수 있어요.", "불 조절이 제일 중요해요.", "간은 마지막에 맞춰 주세요.",
      "남은 건 냉장고에 이틀까지 괜찮아요.", "아이들도 잘 먹어요."],
     ["저도 따라 해 볼게요!", "사진만 봐도 배고프네요", "양념 비율 저장했어요", "오늘 저녁 메뉴 정했다", "이거 진짜 맛있어요"]),
    ("trip", "의 여행 가방", "발길 닿는 대로 다닌 기록", ["국내", "해외", "꿀팁"], ["여행", "사진", "맛집"],
     ["부산 2박 3일 코스", "전주 한옥마을 산책", "도쿄 첫 여행기", "속초 바다 보러", "경주 야경 명소",
      "다낭 가족 여행", "여수 밤바다", "타이베이 야시장", "제주 동쪽 코스", "강릉 당일치기",
      "방콕 자유여행", "통영 케이블카", "후쿠오카 온천", "남해 드라이브", "공항 꿀팁 정리"],
     ["교통편은 미리 예약하는 게 좋아요.", "숙소는 역 근처로 잡았어요.", "날씨가 정말 좋았어요.",
      "사람이 많아서 아침 일찍 가는 걸 추천해요.", "다음엔 더 길게 머물고 싶어요."],
     ["저장해 두고 갑니다", "여기 꼭 가 보고 싶었어요", "사진 너무 예뻐요", "숙소 정보 감사해요", "다음 여행지로 찜!"]),
    ("dev", "의 개발 일지", "공부하고 만든 것들을 정리해요", ["Java", "Spring", "회고"], ["개발", "java", "spring"],
     ["JPA N+1 문제 정리", "Stream API 자주 쓰는 패턴", "Git 브랜치 전략", "REST API 설계 팁",
      "테스트 코드 첫걸음", "Docker로 MySQL 띄우기", "Optional 제대로 쓰기", "스프링 시큐리티 기초",
      "인덱스는 언제 탈까", "트랜잭션 격리 수준", "코드 리뷰에서 배운 것", "예외 처리 원칙",
      "로그 잘 남기는 법", "배포 자동화 해 보기", "첫 사이드 프로젝트 회고"],
     ["예제 코드는 최대한 짧게 적었어요.", "공식 문서를 같이 보면 좋아요.", "처음엔 저도 헷갈렸어요.",
      "실무에서 자주 만나는 상황이에요.", "틀린 부분이 있으면 댓글로 알려 주세요."],
     ["정리 감사합니다", "이 부분 계속 헷갈렸어요", "예제가 이해하기 쉬워요", "북마크 해 둘게요", "다음 글도 기대할게요"]),
    ("photo", "의 사진관", "필름과 디지털로 담은 순간", ["풍경", "인물", "장비"], ["사진", "필름", "카메라"],
     ["노을 찍는 시간", "비 오는 날 골목", "아이폰으로 야경", "단풍 출사 후기", "흑백 필름 현상기",
      "인물 사진 구도", "망원 렌즈 첫 사용", "겨울 바다 스냅", "카페 창가 빛", "삼각대 고르는 법",
      "새벽 안개 풍경", "벚꽃 명소 출사", "필름 스캔 방법", "역광으로 찍기", "중고 카메라 구입기"],
     ["노출은 조금 어둡게 맞췄어요.", "해 지기 30분 전이 제일 좋아요.", "보정은 거의 안 했어요.",
      "삼각대가 있으면 훨씬 편해요.", "같은 장소라도 계절마다 달라요."],
     ["색감이 정말 좋네요", "어떤 렌즈 쓰셨어요?", "저도 이 장소 가 봤어요", "분위기 최고예요", "배경화면으로 쓰고 싶어요"]),
    ("book", "의 책장", "읽은 책과 문장을 모아요", ["소설", "에세이", "자기계발"], ["독서", "책", "서평"],
     ["이번 달 읽은 책", "밑줄 그은 문장들", "출퇴근 독서 습관", "다시 읽고 싶은 소설", "에세이 추천 5권",
      "독서 모임 후기", "전자책 vs 종이책", "올해의 책", "짧은 소설 모음", "책 정리하는 법",
      "추리소설 입문", "도서관 활용기", "시집 한 권", "번역서 고르는 기준", "독서 기록 앱 비교"],
     ["천천히 읽어서 더 좋았어요.", "마지막 장이 오래 남아요.", "가볍게 읽기 좋아요.",
      "생각할 거리가 많은 책이었어요.", "도서관에서 빌려 읽었어요."],
     ["저도 읽어 봐야겠어요", "이 책 정말 좋죠", "문장이 마음에 와닿아요", "추천 감사합니다", "다음 책도 궁금해요"]),
    ("fit", "의 운동 기록", "꾸준히 움직이는 연습", ["헬스", "러닝", "식단"], ["운동", "건강", "러닝"],
     ["첫 5km 완주", "홈트 루틴 공유", "스쿼트 자세 교정", "러닝화 고르기", "단백질 식단 일주일",
      "아침 운동 30일", "하프 마라톤 준비", "스트레칭 10분", "헬스장 등록 후기", "등 운동 기본",
      "비 오는 날 실내 운동", "체중 기록 한 달", "부상 없이 달리기", "플랭크 챌린지", "운동 일지 쓰는 법"],
     ["무리하지 않는 게 제일 중요해요.", "기록을 남기니 동기부여가 돼요.", "처음엔 짧게 시작했어요.",
      "자세 영상을 찍어 보면 도움이 돼요.", "물을 자주 마셔 주세요."],
     ["저도 오늘부터 시작!", "꾸준함이 대단해요", "루틴 따라 해 볼게요", "자극받고 갑니다", "화이팅입니다"]),
]

N_BLOGS = 30
POSTS_PER_BLOG = (12, 24)


def q(s):
    return "'" + s.replace("\\", "\\\\").replace("'", "''") + "'"


def user_id(email):
    return f"(SELECT id FROM users WHERE email = {q(email)})"


def blog_id(slug):
    return f"(SELECT id FROM blogs WHERE slug = {q(slug)})"


def post_id(slug, title):
    return f"(SELECT id FROM posts WHERE blog_id = {blog_id(slug)} AND title = {q(title)} ORDER BY id LIMIT 1)"


out = []
w = out.append

w("-- 블로그 플랫폼 대량 더미 데이터. dummy/make_bulk.py로 만든 파일이라 직접 고치지 않는다.")
w("-- dummy_data.sql 다음에 넣는다. 여러 번 넣어도 같은 행이 두 번 생기지 않는다.")
w(f"-- 회원 {N_USERS}명(bulk001~@example.com, 비밀번호 test1234!), 블로그 {N_BLOGS}개, 글·댓글·좋아요·태그·팔로우·구독")
w("")
w("SET NAMES utf8mb4;")
w("START TRANSACTION;")
w("")

# 1. 회원
users = []
nicks = set(TAKEN_NICKS)
w("-- 1. 회원")
rows = []
for i in range(1, N_USERS + 1):
    email = f"bulk{i:03d}@example.com"
    name = random.choice(LAST) + random.choice(FIRST)
    while True:
        nick = (name[1:] + random.choice(NICK_TAIL))[:10] + (str(i) if random.random() < 0.3 else "")
        nick = nick[:12]
        if nick not in nicks:
            nicks.add(nick)
            break
        nick = None
        name = random.choice(LAST) + random.choice(FIRST)
    phone = "010" + f"{random.randint(0, 99999999):08d}"
    bio = q(f"{nick}입니다. 반가워요!") if random.random() < 0.5 else "NULL"
    keep = 7 if random.random() < 0.15 else 30
    days = random.randint(60, 180)
    users.append(email)
    rows.append(
        f"  ({q(email)}, {q(PW)}, {q(name)}, {q(nick)}, {q(phone)}, {bio}, 'USER', 'ACTIVE', NULL, 0, {keep}, "
        f"NOW() - INTERVAL {days} DAY, NOW() - INTERVAL {days} DAY, NOW() - INTERVAL {days} DAY)"
    )
w("INSERT IGNORE INTO users")
w("  (email, password_hash, name, nickname, phone, bio, role, status, suspended_until, login_fail_count,")
w("   notification_keep_days, terms_agreed_at, privacy_agreed_at, created_at)")
w("VALUES")
w(",\n".join(rows) + ";")
w("")

# 2. 블로그
w("-- 2. 블로그·멤버·카테고리")
blogs = []
owners = random.sample(users, N_BLOGS)
for b in range(N_BLOGS):
    topic = TOPICS[b % len(TOPICS)]
    slug = f"{topic[0]}-{b + 1:02d}"
    owner = owners[b]
    name = f"{random.choice(FIRST)}{topic[1]}"
    r = random.random()
    vis = "PUBLIC" if r < 0.8 else ("LINK_ONLY" if r < 0.9 else "PRIVATE")
    share = q(f"bulk-share-{slug}") if vis == "LINK_ONLY" else "NULL"
    join = "OPEN" if random.random() < 0.6 else "APPROVAL"
    days = random.randint(40, 120)
    others = [u for u in users if u != owner]
    members = random.sample(others, random.randint(4, 20))
    blogs.append(dict(slug=slug, topic=topic, owner=owner, members=members, days=days))
    w(f"INSERT IGNORE INTO blogs (owner_id, slug, name, description, visibility, share_key, join_policy, created_at)")
    w(f"  SELECT {user_id(owner)}, {q(slug)}, {q(name)}, {q(topic[2])}, '{vis}', {share}, '{join}', NOW() - INTERVAL {days} DAY;")
    w(f"INSERT IGNORE INTO blog_members (blog_id, user_id, role, joined_at)")
    w(f"  SELECT {blog_id(slug)}, {user_id(owner)}, 'OWNER', NOW() - INTERVAL {days} DAY;")
    emails = ", ".join(q(m) for m in members)
    w(f"INSERT IGNORE INTO blog_members (blog_id, user_id, role, joined_at)")
    w(f"  SELECT {blog_id(slug)}, u.id, 'MEMBER', NOW() - INTERVAL {days - 3} DAY + INTERVAL (u.id % 30) DAY")
    w(f"  FROM users u WHERE u.email IN ({emails});")
    # 부블로그장 한 명
    if random.random() < 0.5:
        mgr = members[0]
        w(f"UPDATE blog_members SET role = 'MANAGER', manager_since = NOW() - INTERVAL 20 DAY")
        w(f"  WHERE blog_id = {blog_id(slug)} AND user_id = {user_id(mgr)} AND role = 'MEMBER';")
        w(f"INSERT IGNORE INTO blog_manager_permissions (blog_member_id, permission)")
        w(f"  SELECT m.id, 'MANAGE_POSTS' FROM blog_members m WHERE m.blog_id = {blog_id(slug)} AND m.user_id = {user_id(mgr)};")
    for i, c in enumerate(topic[3]):
        w(f"INSERT IGNORE INTO categories (blog_id, name, sort_order) SELECT {blog_id(slug)}, {q(c)}, {i};")
w("")

# 3. 태그
w("-- 3. 태그")
all_tags = sorted({t for topic in TOPICS for t in topic[4]})
for t in all_tags:
    w(f"INSERT IGNORE INTO tags (name) VALUES ({q(t)});")
for b in blogs:
    for t in b["topic"][4][:2]:
        w(f"INSERT IGNORE INTO blog_tags (blog_id, tag_id) SELECT {blog_id(b['slug'])}, (SELECT id FROM tags WHERE name = {q(t)});")
w("")

# 4. 글
w("-- 4. 글 (같은 블로그에 같은 제목이 있으면 건너뛴다)")
posts = []
for b in blogs:
    topic = b["topic"]
    writers = [b["owner"]] + b["members"]
    n = random.randint(*POSTS_PER_BLOG)
    titles = random.sample(topic[5], min(n, len(topic[5])))
    while len(titles) < n:
        base = random.choice(topic[5])
        t = f"{base} {len(titles) - len(topic[5]) + 2}편"
        if len(t) <= 30 and t not in titles:
            titles.append(t)
    for idx, title in enumerate(titles):
        is_notice = idx == 0 and random.random() < 0.4
        if is_notice:
            title = "블로그 이용 안내"
            content = f"{topic[2]}.\n- 서로 존중하는 댓글 부탁드려요.\n- 광고 글은 지워요."
            author = b["owner"]
        else:
            author = random.choice(writers)
            body = random.sample(topic[6], 3)
            content = f"## {title}\n\n" + "\n".join(body) + "\n\n- 한 줄 요약: " + random.choice(topic[6])
        cat = "NULL" if is_notice else (
            f"(SELECT id FROM categories WHERE blog_id = {blog_id(b['slug'])} AND name = {q(random.choice(topic[3]))})"
        )
        days = random.randint(0, min(b["days"] - 1, 90))
        hours = random.randint(0, 23)
        views = random.randint(5, 400)
        posts.append(dict(slug=b["slug"], title=title, author=author, days=days, topic=topic, readers=writers))
        w("INSERT INTO posts (blog_id, author_id, category_id, title, content, is_notice, view_count, created_at, updated_at)")
        w(f"  SELECT {blog_id(b['slug'])}, {user_id(author)}, {cat}, {q(title)}, {q(content)}, {'TRUE' if is_notice else 'FALSE'}, {views}, "
          f"NOW() - INTERVAL {days} DAY - INTERVAL {hours} HOUR, NOW() - INTERVAL {days} DAY - INTERVAL {hours} HOUR")
        w(f"  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM posts WHERE blog_id = {blog_id(b['slug'])} AND title = {q(title)});")
        for t in random.sample(topic[4], random.randint(1, 3)):
            w(f"INSERT IGNORE INTO post_tags (post_id, tag_id) SELECT {post_id(b['slug'], title)}, (SELECT id FROM tags WHERE name = {q(t)});")
w("")

# 5. 댓글
w("-- 5. 댓글·대댓글 (같은 글에 같은 사람이 같은 내용을 쓴 게 있으면 건너뛴다)")
EXTRA = ["좋은 글이에요", "궁금한 게 있어요!", "공감합니다", "잘 보고 갑니다", "다음 편 언제 올라와요?", "완전 유용해요"]
REPLIES = ["감사합니다 :)", "맞아요!", "댓글 고마워요", "다음 글에서 더 자세히 쓸게요", "저도 그렇게 생각해요"]
for p in posts:
    pid = post_id(p["slug"], p["title"])
    for k in range(random.randint(0, 7)):
        author = random.choice(p["readers"])
        content = random.choice(p["topic"][7] + EXTRA)
        hours = k * 3 + 1
        when = f"NOW() - INTERVAL {p['days']} DAY + INTERVAL {hours} HOUR"
        if p["days"] == 0:
            when = f"NOW() - INTERVAL {24 - min(hours, 23)} HOUR"
        w("INSERT INTO comments (post_id, author_id, parent_id, reply_to_user_id, content, created_at, updated_at)")
        w(f"  SELECT {pid}, {user_id(author)}, NULL, NULL, {q(content)}, {when}, {when}")
        w(f"  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM comments WHERE post_id = {pid} AND author_id = {user_id(author)} AND content = {q(content)});")
        if random.random() < 0.35 and author != p["author"]:
            parent = (f"(SELECT id FROM (SELECT id FROM comments WHERE post_id = {pid} AND author_id = {user_id(author)} "
                      f"AND content = {q(content)} AND parent_id IS NULL ORDER BY id LIMIT 1) x)")
            reply = random.choice(REPLIES)
            w("INSERT INTO comments (post_id, author_id, parent_id, reply_to_user_id, content, created_at, updated_at)")
            w(f"  SELECT {pid}, {user_id(p['author'])}, {parent}, {user_id(author)}, {q(reply)}, {when} + INTERVAL 30 MINUTE, {when} + INTERVAL 30 MINUTE")
            w(f"  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM comments WHERE post_id = {pid} AND author_id = {user_id(p['author'])} "
              f"AND content = {q(reply)} AND parent_id = {parent});")
w("")

# 6. 좋아요
w("-- 6. 좋아요")
for p in posts:
    likers = random.sample(p["readers"], random.randint(0, min(12, len(p["readers"]))))
    if not likers:
        continue
    emails = ", ".join(q(u) for u in likers)
    w(f"INSERT IGNORE INTO post_likes (post_id, user_id) SELECT {post_id(p['slug'], p['title'])}, u.id FROM users u WHERE u.email IN ({emails});")
w("")

# 7. 팔로우·구독
w("-- 7. 팔로우·구독")
all_users = users + [f"user{i:02d}@example.com" for i in range(2, 20)]
follows = set()
while len(follows) < 500:
    a, b = random.sample(all_users, 2)
    follows.add((a, b))
for a, b in sorted(follows):
    w(f"INSERT IGNORE INTO user_follows (follower_id, followee_id) SELECT {user_id(a)}, {user_id(b)};")
for b in blogs:
    subs = random.sample(all_users, random.randint(3, 25))
    emails = ", ".join(q(u) for u in subs if u != b["owner"])
    w(f"INSERT IGNORE INTO blog_subscriptions (blog_id, user_id) SELECT {blog_id(b['slug'])}, u.id FROM users u WHERE u.email IN ({emails});")
w("")

w("-- 8. 숫자 맞추기 (멤버 수, 글 수, 구독자 수, 댓글 수, 좋아요 수). updated_at은 그대로 둔다")
w("UPDATE blogs b SET b.member_count = (SELECT COUNT(*) FROM blog_members m WHERE m.blog_id = b.id),")
w("  b.post_count = (SELECT COUNT(*) FROM posts p WHERE p.blog_id = b.id AND p.status = 'PUBLISHED'),")
w("  b.subscriber_count = (SELECT COUNT(*) FROM blog_subscriptions s WHERE s.blog_id = b.id),")
w("  b.updated_at = b.updated_at;")
w("UPDATE posts p SET p.comment_count = (SELECT COUNT(*) FROM comments c WHERE c.post_id = p.id AND c.status = 'ACTIVE'),")
w("  p.like_count = (SELECT COUNT(*) FROM post_likes l WHERE l.post_id = p.id),")
w("  p.updated_at = p.updated_at;")
w("")
w("COMMIT;")

print("\n".join(out))
