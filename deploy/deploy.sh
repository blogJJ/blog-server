#!/usr/bin/env bash
# 서버에서 새 이미지를 띄운다. .github/workflows/deploy.yml이 이 파일, 이미지, 환경변수 파일을
# ~/blog-server/에 올린 뒤 ssh로 실행한다. 직접 실행할 때도 같다:
#
#   bash ~/blog-server/deploy.sh <포트번호> [DB포트번호]
#
# - 이미지: ~/blog-server/image.tar.gz (docker save | gzip)
# - 환경변수: ~/blog-server/app.env (KEY=값 한 줄씩, 따옴표 없이). 이 파일은 본인만 읽게 둔다
# - 컨테이너 안 8080을 서버의 <포트번호>로 연다. nginx가 이 포트로 넘겨준다
# - 새 컨테이너가 2분 안에 /actuator/health에서 UP이 안 되면 마지막으로 성공한 이미지와 환경변수로 되돌린다
# - DB포트번호를 주면 이 서버에 MySQL 컨테이너(<계정>-blog-mysql)도 띄운다. 이미 돌고 있으면 그대로 둔다.
#   DB 이름·계정·비밀번호는 app.env의 DB_NAME, DB_USERNAME, DB_PASSWORD로 처음 한 번 만들고,
#   데이터는 볼륨 <계정>-blog-mysql-data에 남는다. 서버는 도커 네트워크 안에서 db:3306으로 붙고,
#   서버 밖에서는 127.0.0.1:<DB포트번호>로만 열린다(ssh 터널로 접속)
# - bash deploy.sh --load-dummy 는 그 MySQL에 dummy_data.sql을 넣는다
# - 공용 서버라 컨테이너·이미지·볼륨 이름 앞에 접속 계정 이름을 붙여 다른 사람 것과 겹치지 않게 한다
set -euo pipefail

# bash deploy.sh --load-dummy : 이 서버의 MySQL 컨테이너에 ~/blog-server/dummy_data.sql을 넣는다
if [ "${1:-}" = "--load-dummy" ]; then
  OWNER="$(id -un | tr 'A-Z' 'a-z' | tr -c 'a-zA-Z0-9_.\n-' '-')"
  docker exec -i "${OWNER}-blog-mysql" \
    sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" "$MYSQL_DATABASE"' \
    < "${APP_DIR:-$HOME/blog-server}/dummy_data.sql"
  exit 0
fi

PORT="${1:?포트번호를 넣어 주세요. 예: bash deploy.sh 8300}"
if ! [[ "$PORT" =~ ^[0-9]{2,5}$ ]]; then
  echo "포트번호는 숫자여야 해요: $PORT" >&2
  exit 1
fi

DB_PORT="${2:-}"
if [ -n "$DB_PORT" ] && ! [[ "$DB_PORT" =~ ^[0-9]{2,5}$ ]]; then
  echo "DB 포트번호는 숫자여야 해요: $DB_PORT" >&2
  exit 1
fi

APP_DIR="${APP_DIR:-$HOME/blog-server}"
OWNER="$(id -un | tr 'A-Z' 'a-z' | tr -c 'a-zA-Z0-9_.\n-' '-')"
NAME="${OWNER}-blog-server"
IMAGE="${OWNER}/blog-server"
DB_NAME_C="${OWNER}-blog-mysql"
NETWORK="${OWNER}-blog-net"
HEALTH_URL="http://127.0.0.1:${PORT}/actuator/health"

if ! command -v docker >/dev/null 2>&1; then
  echo "이 서버에 docker가 없어요. 서버 관리자에게 docker 설치를 부탁해 주세요." >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "docker를 쓸 권한이 없어요. 서버 관리자에게 이 계정($OWNER)을 docker 그룹에 넣어 달라고 해 주세요." >&2
  exit 1
fi
for f in image.tar.gz app.env; do
  if [ ! -f "$APP_DIR/$f" ]; then
    echo "$APP_DIR/$f 가 없어요." >&2
    exit 1
  fi
done
chmod 600 "$APP_DIR/app.env"

echo "== 이미지 불러오기"
LOADED="$(docker load -i "$APP_DIR/image.tar.gz" | sed -n 's/^Loaded image: //p' | tail -n 1)"
if [ -z "$LOADED" ]; then
  echo "이미지를 불러오지 못했어요." >&2
  exit 1
fi
# app.env에서 KEY의 값을 읽는다
env_value() {
  sed -n "s/^$1=//p" "$APP_DIR/app.env" | tail -n 1
}

start_db() {
  docker network inspect "$NETWORK" >/dev/null 2>&1 || docker network create "$NETWORK" >/dev/null
  if [ "$(docker inspect -f '{{.State.Running}}' "$DB_NAME_C" 2>/dev/null)" = "true" ]; then
    echo "== MySQL은 이미 돌고 있어요 ($DB_NAME_C)"
    return 0
  fi
  if docker container inspect "$DB_NAME_C" >/dev/null 2>&1; then
    echo "== 멈춰 있던 MySQL 다시 켜기 ($DB_NAME_C)"
    docker start "$DB_NAME_C" >/dev/null
  else
    echo "== MySQL 띄우기 ($DB_NAME_C, 127.0.0.1:$DB_PORT)"
    local db user pw env
    db="$(env_value DB_NAME)"
    user="$(env_value DB_USERNAME)"
    pw="$(env_value DB_PASSWORD)"
    if [ -z "$db" ] || [ -z "$user" ] || [ -z "$pw" ]; then
      echo "app.env에 DB_NAME, DB_USERNAME, DB_PASSWORD가 있어야 해요." >&2
      exit 1
    fi
    env="$APP_DIR/mysql.env"
    (
      umask 077
      printf 'MYSQL_DATABASE=%s\nMYSQL_USER=%s\nMYSQL_PASSWORD=%s\nMYSQL_RANDOM_ROOT_PASSWORD=yes\nTZ=Asia/Seoul\n' \
        "$db" "$user" "$pw" > "$env"
    )
    docker run -d \
      --name "$DB_NAME_C" \
      --restart unless-stopped \
      --network "$NETWORK" --network-alias db \
      --env-file "$env" \
      -p "127.0.0.1:${DB_PORT}:3306" \
      -v "${OWNER}-blog-mysql-data:/var/lib/mysql" \
      --log-opt max-size=20m --log-opt max-file=3 \
      mysql:8.4 \
      --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci >/dev/null
    rm -f "$env"
  fi
  # 처음 켤 때는 초기화용 임시 서버(port: 0)가 먼저 뜨므로 3306으로 열린 것을 기다린다
  for _ in $(seq 1 90); do
    if docker logs "$DB_NAME_C" 2>&1 | grep -q 'ready for connections.*port: 3306'; then
      echo "== MySQL 준비됨"
      return 0
    fi
    if [ "$(docker inspect -f '{{.State.Running}}' "$DB_NAME_C" 2>/dev/null)" != "true" ]; then
      break
    fi
    sleep 2
  done
  echo "== MySQL이 뜨지 않았어요. 마지막 로그:" >&2
  docker logs --tail 40 "$DB_NAME_C" >&2 || true
  exit 1
}

if [ -n "$DB_PORT" ]; then
  start_db
fi

# 지금 돌고 있는 이미지는 :previous로 남겨 두었다가 실패하면 되돌린다
if docker image inspect "$IMAGE:latest" >/dev/null 2>&1; then
  docker tag "$IMAGE:latest" "$IMAGE:previous"
fi
docker tag "$LOADED" "$IMAGE:latest"

NET_ARGS=()
if [ -n "$DB_PORT" ]; then
  NET_ARGS=(--network "$NETWORK")
fi

# $1 이미지, $2 환경변수 파일
start() {
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  docker run -d \
    --name "$NAME" \
    --restart unless-stopped \
    --env-file "$2" \
    "${NET_ARGS[@]}" \
    -p "${PORT}:8080" \
    -v "${OWNER}-blog-uploads:/app/uploads" \
    -v "${OWNER}-blog-logs:/app/logs" \
    --log-opt max-size=20m --log-opt max-file=5 \
    "$1" >/dev/null
}

healthy() {
  for _ in $(seq 1 60); do
    if [ "$(docker inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null)" != "true" ]; then
      return 1
    fi
    if command -v curl >/dev/null 2>&1; then
      if curl -fsS --max-time 3 "$HEALTH_URL" 2>/dev/null | grep -q '"UP"'; then
        return 0
      fi
    elif command -v wget >/dev/null 2>&1; then
      if wget -qO- -T 3 "$HEALTH_URL" 2>/dev/null | grep -q '"UP"'; then
        return 0
      fi
    fi
    sleep 2
  done
  # curl도 wget도 없으면 2분 동안 꺼지지 않은 것으로 판단한다
  ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1 \
    && [ "$(docker inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null)" = "true" ]
}

echo "== 새 컨테이너 띄우기 ($NAME, 포트 $PORT)"
start "$IMAGE:latest" "$APP_DIR/app.env"
if healthy; then
  echo "== 배포 완료: $HEALTH_URL 가 UP이에요"
  # 다음 배포가 실패하면 이 조합으로 되돌린다
  cp -p "$APP_DIR/app.env" "$APP_DIR/app.env.ok"
  rm -f "$APP_DIR/image.tar.gz"
  docker image prune -f >/dev/null 2>&1 || true
  exit 0
fi

echo "== 새 컨테이너가 2분 안에 뜨지 않았어요. 마지막 로그:" >&2
docker logs --tail 80 "$NAME" >&2 || true
if docker image inspect "$IMAGE:previous" >/dev/null 2>&1 && [ -f "$APP_DIR/app.env.ok" ]; then
  echo "== 이전 버전으로 되돌려요" >&2
  docker tag "$IMAGE:previous" "$IMAGE:latest"
  start "$IMAGE:latest" "$APP_DIR/app.env.ok"
  if healthy; then
    echo "== 이전 버전으로 돌아왔어요" >&2
    exit 1
  fi
  echo "== 이전 버전도 뜨지 않았어요" >&2
fi
# 계속 재시작하며 공용 서버 자원을 쓰지 않게 멈춰 둔다
echo "== 컨테이너를 멈췄어요. 원인을 고친 뒤 다시 배포해 주세요" >&2
docker rm -f "$NAME" >/dev/null 2>&1 || true
exit 1
