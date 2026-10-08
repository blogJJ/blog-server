#!/usr/bin/env bash
# DB 변경 규칙 검사 (T143, OPS-08, D-108)
#  1. 기준 브랜치에 이미 있는 Flyway 파일을 고치거나 지우거나 이름을 바꾸면 실패
#  2. 모든 파일 이름이 V번호__설명.sql 형식인지
#  3. 같은 번호가 두 번 나오지 않는지
# 사용: check-migrations.sh <기준 커밋>   (CI는 PR의 base 브랜치를 넘긴다)
set -euo pipefail

base="${1:?기준 커밋을 넘겨야 합니다}"
dir="src/main/resources/db/migration"
fail=0

changed="$(git diff --name-status --diff-filter=MDR "$base"...HEAD -- "$dir")"
if [[ -n "$changed" ]]; then
  echo "::error::이미 merge된 Flyway 파일은 고치거나 지울 수 없습니다. 다음 번호로 새 파일을 추가하세요."
  echo "$changed"
  fail=1
fi

versions=()
for path in "$dir"/*; do
  name="$(basename "$path")"
  if [[ ! "$name" =~ ^V([0-9]+)__[A-Za-z0-9_]+\.sql$ ]]; then
    echo "::error file=$path::파일 이름은 V번호__설명.sql 형식이어야 합니다 (예: V3__add_blog_theme.sql): $name"
    fail=1
    continue
  fi
  versions+=("$((10#${BASH_REMATCH[1]}))")
done

dups="$(printf '%s\n' "${versions[@]}" | sort -n | uniq -d)"
if [[ -n "$dups" ]]; then
  echo "::error::같은 번호의 Flyway 파일이 있습니다: V$(echo "$dups" | paste -sd, -)"
  fail=1
fi

if [[ $fail -eq 0 ]]; then
  echo "Flyway 파일 검사 통과 (${#versions[@]}개)"
fi
exit $fail
