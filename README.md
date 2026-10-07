# blog-server

티스토리형 블로그 플랫폼(메인블로그)의 서버입니다. Spring Boot 하나가 API와 정적 화면을 함께 내려주고, MySQL 8 한 곳에 모든 데이터를 둡니다.

요구사항·설계·작업 목록은 [blog-docs](https://github.com/blogJJ/blog-docs) 저장소에 있습니다. 구현은 [tasks.md](https://github.com/blogJJ/blog-docs/blob/main/specs/001-main-blog/tasks.md)의 작업 번호(T001~T141) 순서대로 진행합니다.

## 필요한 것

| 도구 | 버전 | 용도 |
| --- | --- | --- |
| JDK | 21 | 서버 실행·빌드 |
| Docker Desktop | 최신 | 로컬 MySQL 실행 |
| IntelliJ IDEA | 최신 (Community도 됨) | 개발 |

Gradle은 따로 설치하지 않아도 됩니다. 저장소의 `./gradlew`가 맞는 버전을 받아 씁니다.

## 처음 실행하기

```bash
# 1. MySQL 띄우기 (처음 한 번은 이미지를 받느라 조금 걸림)
docker compose up -d

# 2. 비밀값 파일 만들기
cp .env.example .env
#    .env를 열어 JWT_SECRET 등을 채운다. 메일·Turnstile 키는 그 기능을 만들 때 채워도 된다

# 3. 서버 실행
./gradlew bootRun
```

서버를 처음 켜면 Flyway가 `db/migration`의 SQL을 차례로 실행해 테이블 33개와 인덱스를 만듭니다(로그에 `Successfully applied 2 migrations`). 다음부터는 새 파일만 실행합니다. 서버 로그에 `Started BlogServerApplication`이 보이면 성공입니다. 지금은 http://localhost:8080/login 에 Spring Security 기본 로그인 창이 뜨고 다른 주소는 401이 나오는 것이 정상입니다(보안 설정은 T016에서 만듦).

Windows에서는 `./gradlew` 대신 `gradlew.bat`을 씁니다.

## 자주 쓰는 명령

| 명령 | 하는 일 |
| --- | --- |
| `./gradlew build` | 컴파일, 코드 모양 검사, 테스트, 실행 파일(jar) 만들기 |
| `./gradlew spotlessApply` | 코드 모양을 google-java-format에 맞춰 자동으로 고침. 커밋 전에 한 번 |
| `./gradlew bootRun` | 서버 실행 |
| `docker compose down` | MySQL 끄기 (데이터는 남음) |
| `docker compose down -v` | MySQL 끄고 데이터까지 지우기 |

## 폴더 구조

```text
src/main/java/com/blog/
├── auth/      회원·인증 (USR, SEC-01~05, 13)
├── blog/      블로그·멤버·폐쇄·블랙리스트 (BLG)
├── board/     공통 게시판: 글, 댓글, 태그, 이미지 (BRD-01~07, 11)
├── main/      통합 검색, 메인 피드, 공지 (BRD-08~10)
├── social/    팔로우, 구독, 알림, 차단, 신고 (SOC)
├── admin/     메인 관리자 (ADM)
├── batch/     매일 04:00 배치
└── common/    보안 설정, 마스킹, 파일 저장, 요청 제한
src/main/resources/
├── application.yml   설정 (값은 환경변수나 .env로 덮어씀)
├── db/migration/     Flyway SQL. V1 테이블(erd_tables.sql), V2 인덱스(add_indexes.sql)
└── static/           정적 HTML + JS 화면
```

## 지킬 것

- 비밀값은 `.env`나 서버 환경변수에만 둡니다. `.env`는 `.gitignore`에 들어 있어 올라가지 않습니다.
- DB 구조는 Flyway SQL로만 바꿉니다. 테이블·컬럼을 바꾸려면 먼저 blog-docs의 `erd_tables.sql`을 고친 뒤, 이미 올라간 V 파일은 고치지 말고 다음 번호(`V3__설명.sql`)로 새 파일을 더합니다. 이미 실행된 파일을 고치면 Flyway가 체크섬이 다르다며 서버를 띄우지 않습니다.
- PR을 올리면 GitHub Actions(`.github/workflows/ci.yml`)가 `./gradlew build`를 돌립니다. 빨간 X가 뜨면 합치기 전에 고칩니다.
- 시간대는 Asia/Seoul입니다. 서버 시작 때와 DB 연결, JSON에 모두 지정되어 있습니다.
- 원칙은 blog-docs의 [constitution.md](https://github.com/blogJJ/blog-docs/blob/main/.specify/memory/constitution.md)를 따릅니다(보안 먼저, 블로그별 역할은 요청마다 DB로 확인 등).
