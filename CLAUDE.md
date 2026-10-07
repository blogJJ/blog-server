# blog-server

메인블로그 플랫폼 서버(Spring Boot 4, Java 21, MySQL 8). 요구사항과 작업 목록은 blogJJ/blog-docs에 있다.

- 작업은 blog-docs `specs/001-main-blog/tasks.md`의 T-번호 단위로 하고, 커밋·PR에 번호를 적는다.
- 근거는 blog-docs의 spec.md(요구사항 ID), research.md(D-번호), data-model.md(테이블·상태 전이). 원칙은 `.specify/memory/constitution.md`.
- DB 구조는 `src/main/resources/db/migration`의 Flyway SQL로만 바꾼다. 기준은 blog-docs `docs/02-database/schema/erd_tables.sql`이고, 테이블·컬럼을 바꾸려면 그쪽을 먼저 고친다. `ddl-auto`는 validate.
- 패키지는 `com.blog.{auth,blog,board,main,social,admin,batch,common}`, 모듈마다 `domain/`, `repository/`, `service/`, `api/`.
- 커밋 전에 `./gradlew spotlessApply build`.
- 비밀값은 `.env` 또는 환경변수. 저장소에 올리지 않는다.
