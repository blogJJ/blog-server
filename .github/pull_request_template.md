<!-- 제목은 T-번호로 시작합니다. 예: T162: 올린 사진을 줄여 WebP로 저장 -->
<!-- feature/* → develop, release/* · hotfix/* → main -->

## 무엇을 / 왜

-

## 관련 T-번호

- T (blog-docs specs/001-main-blog/tasks.md)
- 근거: 요구사항 ID / D-번호

## 확인한 것

- [ ] `./gradlew spotlessApply build` 통과
- [ ] 화면이나 API를 직접 열어 확인
- [ ] DB를 바꿨다면 새 Flyway 파일(V 번호)을 더하고 blog-docs erd_tables.sql도 고침
- [ ] 사용자에게 보일 변경이면 release-notes.json 맨 위에 버전 추가 (release/hotfix)
- [ ] 비밀값을 커밋하지 않음
