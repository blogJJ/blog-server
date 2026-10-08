# blog-server

티스토리형 블로그 플랫폼(메인블로그)의 서버입니다. Spring Boot 하나가 API와 정적 화면을 함께 내려주고, MySQL 8 한 곳에 모든 데이터를 둡니다.

요구사항·설계·작업 목록은 [blog-docs](https://github.com/blogJJ/blog-docs) 저장소에 있습니다. 구현은 [tasks.md](https://github.com/blogJJ/blog-docs/blob/main/specs/001-main-blog/tasks.md)의 작업 번호(T001~T147) 순서대로 진행합니다.

## 필요한 것

| 도구 | 버전 | 용도 |
| --- | --- | --- |
| JDK | 21 | 서버 실행·빌드 |
| Docker Desktop | 최신 | 로컬 MySQL 실행, 테스트(Testcontainers가 MySQL을 띄움) |
| IntelliJ IDEA | 최신 (Community도 됨) | 개발 |

Gradle은 따로 설치하지 않아도 됩니다. 저장소의 `./gradlew`가 맞는 버전을 받아 씁니다.

## 처음 실행하기

```bash
# 1. MySQL 띄우기 (처음 한 번은 이미지를 받느라 조금 걸림)
docker compose up -d

# 2. 비밀값 파일 만들기
cp .env.example .env
#    .env를 열어 JWT_SECRET(32바이트 이상)을 꼭 채운다. 비어 있으면 서버가 뜨지 않는다.
#    메일·Turnstile 키는 그 기능을 만들 때 채워도 된다

# 3. 서버 실행
./gradlew bootRun
```

서버를 처음 켜면 Flyway가 `db/migration`의 SQL을 차례로 실행해 테이블 33개와 인덱스를 만듭니다(로그에 `Successfully applied 2 migrations`). 다음부터는 새 파일만 실행합니다. 서버 로그에 `Started BlogServerApplication`이 보이면 성공입니다. 아직 API가 없어서 http://localhost:8080/api/... 주소는 `{"code":"NOT_FOUND", ...}`가 나오는 것이 정상입니다.

Windows에서는 `./gradlew` 대신 `gradlew.bat`을 씁니다.

## 실행 환경 (local, prod)

| 프로필 | 언제 | 다른 점 |
| --- | --- | --- |
| local | 개발자 컴퓨터. `./gradlew bootRun`이 자동으로 고름 | Swagger 열림, 쿠키 Secure 꺼짐(http://localhost용), SQL 로그 |
| prod | 운영 서버. 서버 환경변수 `SPRING_PROFILES_ACTIVE=prod` | 비밀값(JWT_SECRET, DB_PASSWORD, MAIL_PASSWORD, TURNSTILE_SECRET_KEY)이 비었거나 예시 값이면 서버가 뜨지 않음, http → https로 돌려보냄, 로그 파일 |
| (없음) | 프로필을 빠뜨렸을 때 | `application.yml`의 운영 기준 값: Swagger 닫힘, 쿠키 Secure |

IntelliJ에서 `BlogServerApplication`을 바로 실행할 때는 Run Configuration의 Active profiles에 `local`을 적습니다.

| 주소 | 설명 |
| --- | --- |
| http://localhost:8080/swagger-ui.html | API 문서(Swagger). local에서만 열림 |
| http://localhost:8080/actuator/health | 서버 상태. `{"status":"UP"}`이면 정상(DB 연결 포함) |

로그는 모든 줄에 8자리 요청 ID가 붙고, 응답 헤더 `X-Request-Id`와 500 오류의 `errorId`가 같은 값입니다. local은 콘솔에만, 그 밖에는 `LOG_PATH`(기본 `./logs`)에 날짜별 파일로 남기고 30일 뒤 지웁니다.

## 운영 서버 배포

- main의 CI가 통과하면 `.github/workflows/deploy.yml`이 도커 이미지를 만들어 ssh로 서버에 올리고 `deploy/deploy.sh`로 띄운다. Actions 탭 > Deploy > Run workflow로 직접 돌릴 수도 있다.
- 필요한 값은 저장소 Settings > Secrets and variables > Actions의 Repository secrets에 넣는다. 이름 목록은 `deploy.yml` 맨 위에 있다. DB는 Crowfoot의 MySQL #2(빈 DB)이고, 서버가 처음 뜰 때 Flyway가 테이블을 만든다. `DB_ADDRESS`는 s4.java21.net이 아니라 내부 IP `10.116.64.14`를 넣는다. 실습 서버의 /etc/hosts는 그 이름을 내부 IP로 바꿔 주지만 도커 컨테이너 안에서는 외부 주소로 가서 `No route to host`가 난다.
- 더미 데이터는 Run workflow에서 "더미 데이터 넣기"를 켜고 돌리면 서버가 뜬 뒤 서버 안에서 mysql 클라이언트 컨테이너로 `dummy/dummy_data.sql`을 넣는다. 더미 관리자(admin@example.com)도 비밀번호가 test1234!라서, 실제로 쓰기 시작하면 바꾼다.
- 서버에서는 `~/blog-server/`에 파일이 있고, 컨테이너 이름은 `<계정>-blog-server`다. 로그는 `docker logs <계정>-blog-server`. 새 버전이 2분 안에 뜨지 않으면 마지막으로 성공한 버전으로 되돌린다.
- nginx 설정(설명서 5단계)은 이 저장소 밖에서 한다. nginx는 `X-Forwarded-Proto`를 넘겨야 한다(안 넘기면 https로 돌려보낸다).

## 자주 쓰는 명령

| 명령 | 하는 일 |
| --- | --- |
| `./gradlew build` | 컴파일, 코드 모양 검사, 테스트, 실행 파일(jar) 만들기. 통합 테스트가 Docker로 MySQL을 띄우므로 Docker가 켜져 있어야 함 |
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
- PR을 올리면 GitHub Actions(`.github/workflows/ci.yml`)가 `./gradlew build`를 돌리고, 이미 merge된 Flyway 파일을 고쳤는지 검사합니다(`migrations`). 빨간 X가 뜨면 합치기 전에 고칩니다.
- Dependabot이 매주 월요일 라이브러리 업데이트 PR을 엽니다. CI가 통과하면 확인하고 merge합니다(자동 merge 없음).
- 시간대는 Asia/Seoul입니다. 서버 시작 때와 DB 연결, JSON에 모두 지정되어 있습니다.
- 원칙은 blog-docs의 [constitution.md](https://github.com/blogJJ/blog-docs/blob/main/.specify/memory/constitution.md)를 따릅니다(보안 먼저, 블로그별 역할은 요청마다 DB로 확인 등).
