#!/usr/bin/env bash
# 서버에서 새 이미지를 띄운다. .github/workflows/deploy.yml이 이 파일, 이미지, 환경변수 파일을
# ~/blog-server/에 올린 뒤 ssh로 실행한다. 직접 실행할 때도 같다:
#
#   bash ~/blog-server/deploy.sh <포트번호>
#
# - 이미지: ~/blog-server/image.tar.gz (docker save | gzip)
# - 환경변수: ~/blog-server/app.env (KEY=값 한 줄씩, 따옴표 없이). 이 파일은 본인만 읽게 둔다
# - 배포 중에도 멈추지 않게 블루그린으로 띄운다. 서버의 <포트번호>에는 전용 프록시(nginx 컨테이너)가 있고,
#   그 뒤에서 서버 컨테이너 blue와 green이 번갈아 돈다. 서버 nginx는 지금처럼 이 포트로 넘겨주면 되고 설정을 바꾸지 않는다
# - 새 컨테이너를 먼저 띄우고 2분 안에 /actuator/health가 UP이 되면 프록시를 새 컨테이너로 돌린 뒤 옛 컨테이너를 내린다.
#   UP이 안 되면 새 컨테이너만 지우고 옛 컨테이너가 계속 응답한다
# - 잠깐 두 버전이 같은 DB를 쓰므로, Flyway 마이그레이션은 옛 버전이 계속 돌 수 있게 만든다
#   (칼럼·테이블 추가는 괜찮고, 지우거나 이름을 바꾸는 것은 다음 배포로 미룬다)
# - 공용 서버라 컨테이너·이미지·볼륨 이름 앞에 접속 계정 이름을 붙여 다른 사람 것과 겹치지 않게 한다
set -euo pipefail

# bash deploy.sh --load-dummy : ~/blog-server/dummy_data.sql, dummy_data_bulk.sql, dummy_ops.sql을 차례로 DB에 넣는다.
# 서버 안에서 mysql 클라이언트 컨테이너를 띄워 넣는다(DB가 내부 주소라 깃허브에서는 붙지 못한다).
# 접속 정보는 app.env의 DB_HOST, DB_PORT, DB_NAME, DB_USERNAME, DB_PASSWORD를 쓴다
if [ "${1:-}" = "--load-dummy" ]; then
  APP_DIR="${APP_DIR:-$HOME/blog-server}"
  for f in dummy_data.sql dummy_data_bulk.sql dummy_ops.sql; do
    [ -f "$APP_DIR/$f" ] || continue
    echo "== $f 넣기"
    docker run --rm -i --env-file "$APP_DIR/app.env" mysql:8.4 \
      sh -c 'MYSQL_PWD="$DB_PASSWORD" exec mysql --default-character-set=utf8mb4 \
        -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USERNAME" "$DB_NAME"' \
      < "$APP_DIR/$f"
  done
  exit 0
fi

PORT="${1:?포트번호를 넣어 주세요. 예: bash deploy.sh 8300}"
if ! [[ "$PORT" =~ ^[0-9]{2,5}$ ]]; then
  echo "포트번호는 숫자여야 해요: $PORT" >&2
  exit 1
fi

APP_DIR="${APP_DIR:-$HOME/blog-server}"
OWNER="$(id -un | tr 'A-Z' 'a-z' | tr -c 'a-zA-Z0-9_.\n-' '-')"
NAME="${OWNER}-blog-server"
IMAGE="${OWNER}/blog-server"
NET="${OWNER}-blog-net"
PROXY="${OWNER}-blog-proxy"
PROXY_IMAGE="nginx:1.27-alpine"
PROXY_DIR="$APP_DIR/proxy"
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
# 지금 돌고 있는 이미지는 :previous로 남겨 둔다. 손으로 되돌릴 때 쓴다
if docker image inspect "$IMAGE:latest" >/dev/null 2>&1; then
  docker tag "$IMAGE:latest" "$IMAGE:previous"
fi
docker tag "$LOADED" "$IMAGE:latest"

docker network inspect "$NET" >/dev/null 2>&1 || docker network create "$NET" >/dev/null
mkdir -p "$PROXY_DIR"

# 지금 프록시가 넘겨주는 쪽(blue/green). 프록시가 없으면(블루그린 전 배포) 빈 값
ACTIVE=""
if [ "$(docker inspect -f '{{.State.Running}}' "$PROXY" 2>/dev/null)" = "true" ] \
  && [ -f "$PROXY_DIR/default.conf" ]; then
  ACTIVE="$(sed -n 's/.*set \$app http:\/\/.*-\(blue\|green\):8080;.*/\1/p' "$PROXY_DIR/default.conf")"
fi
if [ "$ACTIVE" = "blue" ]; then NEXT="green"; else NEXT="blue"; fi
NEXT_NAME="${NAME}-${NEXT}"
# 네트워크 안에서 부르는 이름. 컨테이너 이름에는 계정의 _가 들어가는데, Tomcat은 _가 든 Host를 400으로 막는다
NEXT_HOST="app-${NEXT}"

# 서버 컨테이너는 포트를 열지 않고 프록시와 같은 네트워크에만 붙는다
start() {
  docker rm -f "$NEXT_NAME" >/dev/null 2>&1 || true
  docker run -d \
    --name "$NEXT_NAME" \
    --restart unless-stopped \
    --network "$NET" \
    --network-alias "$NEXT_HOST" \
    --env-file "$APP_DIR/app.env" \
    -v "${OWNER}-blog-uploads:/app/uploads" \
    -v "${OWNER}-blog-logs:/app/logs" \
    --log-opt max-size=20m --log-opt max-file=5 \
    "$IMAGE:latest" >/dev/null
}

# 네트워크 안에서 새 컨테이너의 상태 확인 주소를 읽는다
probe() {
  docker run --rm --network "$NET" "$PROXY_IMAGE" \
    wget -qO- -T 3 "http://${NEXT_HOST}:8080/actuator/health" 2>/dev/null | grep -q '"UP"'
}

# DB 변경(Flyway)이 잠금을 오래 기다리면 앱이 스스로 멈추므로(spring.flyway.init-sqls), 이 한도는 앱이 뜨는 시간만 넉넉히 덮으면 된다
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-240}"

# 확인 한 번에 컨테이너를 하나 띄우느라 몇 초씩 걸리므로 횟수가 아니라 시간(기본 4분, HEALTH_TIMEOUT 초)으로 끊는다.
# 기다리는 동안 출력이 없으면 ssh 연결이 끊기므로 20초마다 진행 상황을 찍는다
healthy() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT)) next_note=$((SECONDS + 20))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if [ "$(docker inspect -f '{{.State.Running}}' "$NEXT_NAME" 2>/dev/null)" != "true" ]; then
      return 1
    fi
    if probe; then
      return 0
    fi
    if [ "$SECONDS" -ge "$next_note" ]; then
      echo "   아직 뜨는 중 (${SECONDS}초)"
      next_note=$((SECONDS + 20))
    fi
    sleep 2
  done
  return 1
}

# 서버 nginx에서 온 X-Forwarded-*를 그대로 넘긴다. 서버가 실제 사용자 IP와 http/https를 알 수 있게
write_proxy_conf() {
  cat > "$PROXY_DIR/default.conf.new" <<CONF
resolver 127.0.0.11 valid=10s ipv6=off;
server {
    listen 8080;
    client_max_body_size 20m;
    access_log off;

    location / {
        set \$app http://${NEXT_HOST}:8080;
        proxy_pass \$app;
        proxy_http_version 1.1;
        proxy_set_header Host              \$http_host;
        proxy_set_header X-Real-IP         \$http_x_real_ip;
        proxy_set_header X-Forwarded-For   \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$http_x_forwarded_proto;
        proxy_set_header Upgrade           \$http_upgrade;
        proxy_set_header Connection        \$http_connection;
        proxy_read_timeout 60s;
    }
}
CONF
  # 폴더째 붙여 두었으므로 mv로 바꾸면 컨테이너도 새 파일을 본다
  mv "$PROXY_DIR/default.conf.new" "$PROXY_DIR/default.conf"
}

through_proxy() {
  for _ in $(seq 1 15); do
    if command -v curl >/dev/null 2>&1; then
      curl -fsS --max-time 3 "$HEALTH_URL" 2>/dev/null | grep -q '"UP"' && return 0
    elif command -v wget >/dev/null 2>&1; then
      wget -qO- -T 3 "$HEALTH_URL" 2>/dev/null | grep -q '"UP"' && return 0
    else
      return 0
    fi
    sleep 2
  done
  return 1
}

echo "== 새 컨테이너 띄우기 ($NEXT_NAME). 지금 응답 중: ${ACTIVE:-이전 방식 컨테이너}"
start
if ! healthy; then
  echo "== 새 컨테이너가 ${HEALTH_TIMEOUT}초 안에 뜨지 않았어요. 마지막 로그:" >&2
  docker logs --tail 80 "$NEXT_NAME" >&2 || true
  docker rm -f "$NEXT_NAME" >/dev/null 2>&1 || true
  echo "== 새 컨테이너를 지웠어요. 지금까지 돌던 서버가 계속 응답해요. 원인을 고친 뒤 다시 배포해 주세요" >&2
  exit 1
fi

echo "== 프록시를 $NEXT_NAME 로 돌리기"
write_proxy_conf
if [ -n "$ACTIVE" ]; then
  docker exec "$PROXY" nginx -t >/dev/null 2>&1
  # reload는 처리 중인 요청을 끝까지 보내고 새 요청부터 새 컨테이너로 넘긴다
  docker exec "$PROXY" nginx -s reload >/dev/null 2>&1
else
  # 처음 블루그린으로 바꿀 때만: 포트를 쓰던 이전 방식 컨테이너를 내리고 프록시를 띄운다(1~2초 끊김)
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  docker rm -f "$PROXY" >/dev/null 2>&1 || true
  docker run -d \
    --name "$PROXY" \
    --restart unless-stopped \
    --network "$NET" \
    -p "${PORT}:8080" \
    -v "$PROXY_DIR:/etc/nginx/conf.d:ro" \
    --log-opt max-size=10m --log-opt max-file=3 \
    "$PROXY_IMAGE" >/dev/null
  # 프록시가 없는 동안 남아 있던 다른 색 컨테이너가 있으면 지운다
  if [ "$NEXT" = "blue" ]; then OTHER="green"; else OTHER="blue"; fi
  docker rm -f "${NAME}-${OTHER}" >/dev/null 2>&1 || true
fi

if ! through_proxy; then
  echo "== 프록시를 거쳐 $HEALTH_URL 가 UP이 아니에요. 프록시 로그:" >&2
  docker logs --tail 40 "$PROXY" >&2 || true
  exit 1
fi

if [ -n "$ACTIVE" ]; then
  echo "== 옛 컨테이너 내리기 (${NAME}-${ACTIVE})"
  # 스프링 graceful shutdown이 남은 요청을 마칠 시간을 준다
  docker stop -t 30 "${NAME}-${ACTIVE}" >/dev/null 2>&1 || true
  docker rm "${NAME}-${ACTIVE}" >/dev/null 2>&1 || true
fi

echo "== 배포 완료: $HEALTH_URL 가 UP이에요 ($NEXT_NAME)"
rm -f "$APP_DIR/image.tar.gz"
docker image prune -f >/dev/null 2>&1 || true
