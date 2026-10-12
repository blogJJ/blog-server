#!/usr/bin/env bash
# DB 전체 백업 (T139, OPS-01, D-101, D-121).
# 서버에서 돈다. 깃허브 Actions의 Backup 워크플로가 매일 03:00(Asia/Seoul)에 ssh로 부르고,
# 만든 파일을 받아 암호화해서 서버 밖(Actions 아티팩트, 7일 보관)에 둔다.
#
#   bash ~/blog-server/db-backup.sh        백업 파일 경로를 마지막 줄에 찍는다
#
# DB 접속 정보는 배포 때 올린 ~/blog-server/app.env(DB_HOST, DB_PORT, DB_NAME, DB_USERNAME, DB_PASSWORD)를 쓴다.
# DB가 학교 내부 주소라 깃허브에서 바로 붙지 못해 서버 안에서 mysql:8.4 컨테이너로 덤프한다.
# 서버에도 같은 파일을 7일치 남긴다. 폴더와 파일은 이 계정만 읽는다.
set -euo pipefail

APP_DIR="${APP_DIR:-$HOME/blog-server}"
BACKUP_DIR="$APP_DIR/backups"
KEEP_DAYS=7

if [ ! -f "$APP_DIR/app.env" ]; then
  echo "$APP_DIR/app.env 가 없어요. 먼저 배포를 한 번 해 주세요." >&2
  exit 1
fi

umask 077
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

file="$BACKUP_DIR/blog-$(TZ=Asia/Seoul date +%Y%m%d-%H%M).sql.gz"
tmp="$file.part"
trap 'rm -f "$tmp"' EXIT

# --single-transaction: 서버를 멈추지 않고 한 시점의 데이터를 읽는다(InnoDB)
# --no-tablespaces: 이 계정에는 PROCESS 권한이 없어도 된다
# --set-gtid-purged=OFF: 다른 DB에 복구할 때 GTID 오류가 나지 않게
# 프로시저·트리거·이벤트는 쓰지 않아서 덤프하지 않는다(그 권한이 없는 계정이어도 된다)
docker run --rm -i --env-file "$APP_DIR/app.env" mysql:8.4 \
  sh -c 'MYSQL_PWD="$DB_PASSWORD" exec mysqldump --default-character-set=utf8mb4 \
    --single-transaction --quick --skip-triggers \
    --no-tablespaces --set-gtid-purged=OFF \
    -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USERNAME" "$DB_NAME"' \
  | gzip -9 > "$tmp"

# 덤프가 끝까지 쓰였는지 확인한다. 중간에 끊기면 마지막 줄이 없다
if ! gzip -dc "$tmp" | tail -n 1 | grep -q '^-- Dump completed'; then
  echo "덤프가 끝까지 쓰이지 않았어요." >&2
  exit 1
fi
mv "$tmp" "$file"
trap - EXIT

find "$BACKUP_DIR" -name 'blog-*.sql.gz' -type f -mtime +$((KEEP_DAYS - 1)) -delete

echo "백업 크기: $(du -h "$file" | cut -f1), 서버에 남은 백업: $(find "$BACKUP_DIR" -name 'blog-*.sql.gz' | wc -l)개" >&2
echo "$file"
