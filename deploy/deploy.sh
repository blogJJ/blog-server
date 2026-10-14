#!/usr/bin/env bash
# 서버에서 새 이미지를 띄운다. .github/workflows/deploy.yml이 이 파일, 이미지, 환경변수 파일을
# ~/blog-server/에 올린 뒤 ssh로 실행한다. 직접 실행할 때도 같다:
#
#   bash ~/blog-server/deploy.sh <포트번호>
#
# - 이미지: ~/blog-server/image.tar.gz (docker save | gzip)
# - 환경변수: ~/blog-server/app.env (KEY=값 한 줄씩, 따옴표 없이). 이 파일은 본인만 읽게 둔다
# - 이중화: 서버의 <포트번호>에는 전용 프록시(nginx 컨테이너)가 있고, 그 뒤에서 서버 컨테이너 app-1, app-2(REPLICAS, 기본 2)가
#   늘 같이 돈다. 프록시가 둘에 나눠 보내고(같은 IP는 같은 앱으로), 한 대가 응답하지 않으면 다른 쪽으로 넘긴다.
#   서버 nginx는 지금처럼 이 포트로 넘겨주면 되고 설정을 바꾸지 않는다
# - 무중단 배포: 한 대씩 바꾼다(롤링). 바꿀 앱을 프록시에서 빼고 처리 중인 요청을 마치게 한 뒤(30초) 내리고, 새 이미지로 띄워
#   HEALTH_TIMEOUT(기본 240초) 안에 /actuator/health가 UP이 되면 프록시에 다시 넣고 다음 대로 넘어간다. 그동안 다른 앱이 응답한다.
#   UP이 안 되면 그 컨테이너만 지우고 멈춘다. 아직 바꾸지 않은 쪽이 계속 응답하고, 다음 배포는 빈 자리부터 채운다
# - 잠깐 두 버전이 같은 DB를 쓰므로, Flyway 마이그레이션은 옛 버전이 계속 돌 수 있게 만든다
#   (칼럼·테이블 추가는 괜찮고, 지우거나 이름을 바꾸는 것은 다음 배포로 미룬다)
# - 공용 서버라 컨테이너·이미지·볼륨 이름 앞에 접속 계정 이름을 붙여 다른 사람 것과 겹치지 않게 한다
#
# 전체 흐름 (아래 번호 붙은 칸과 같은 순서)
#   0. --load-dummy면 더미 데이터만 넣고 끝
#   1. 준비 확인: 포트번호, docker 권한, 올라온 파일
#   2. 이미지 불러오기: image.tar.gz → :latest (돌던 것은 :previous로 남김)
#   3. 지금 상태 읽기: 프록시가 떠 있는지, 어느 앱에 넘겨주고 있는지
#   4. 쓰는 함수들: 앱 띄우기, 상태 확인, 프록시 설정 쓰기
#   5-가. 처음(프록시 없음): 앱을 다 띄운 뒤 프록시를 띄운다
#   5-나. 평소(프록시 있음): 앱을 한 대씩 빼고 → 바꾸고 → 다시 넣는다
#   6. 프록시를 거쳐 /actuator/health가 UP인지 보고 정리
#
# 요청이 들어오는 길:
#   사용자 → 서버 nginx(도메인, https) → 이 서버의 <포트번호> → 프록시 컨테이너 → app-1 또는 app-2 (컨테이너 8080)

# -e: 명령 하나라도 실패하면 바로 멈춘다 / -u: 정하지 않은 변수를 쓰면 멈춘다 / -o pipefail: 파이프 중간 실패도 실패로 본다
set -euo pipefail

# ---------- 0. 더미 데이터만 넣기 ----------
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

# ---------- 1. 준비 확인 ----------
# 첫 번째 인자가 포트번호. 비어 있으면 안내를 찍고 멈춘다
PORT="${1:?포트번호를 넣어 주세요. 예: bash deploy.sh 8300}"
if ! [[ "$PORT" =~ ^[0-9]{2,5}$ ]]; then
  echo "포트번호는 숫자여야 해요: $PORT" >&2
  exit 1
fi

# 이름 규칙. 계정 이름을 소문자로 바꾸고 docker 이름에 못 쓰는 글자는 -로 바꿔 앞에 붙인다
APP_DIR="${APP_DIR:-$HOME/blog-server}"   # 올라온 파일이 있는 폴더
OWNER="$(id -un | tr 'A-Z' 'a-z' | tr -c 'a-zA-Z0-9_.\n-' '-')"
NAME="${OWNER}-blog-server"            # 앱 컨테이너 이름 앞부분. 실제 이름은 -1, -2가 붙는다
IMAGE="${OWNER}/blog-server"           # 앱 이미지 이름 (:latest, :previous)
NET="${OWNER}-blog-net"                # 프록시와 앱만 있는 docker 네트워크. 앱은 이 안에서만 보인다
PROXY="${OWNER}-blog-proxy"            # 프록시(로드밸런서) 컨테이너 이름
PROXY_IMAGE="nginx:1.27-alpine"        # 프록시로 쓰는 nginx 이미지. 상태 확인용 wget도 이 이미지로 돌린다
PROXY_DIR="$APP_DIR/proxy"             # 프록시 설정 파일(default.conf)을 두는 폴더. 컨테이너에 읽기 전용으로 붙인다
HEALTH_URL="http://127.0.0.1:${PORT}/actuator/health"   # 프록시를 거쳐 앱 상태를 묻는 주소

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
chmod 600 "$APP_DIR/app.env"   # 비밀값이 든 파일이라 이 계정만 읽고 쓰게

# ---------- 2. 이미지 불러오기 ----------
# docker load가 찍는 "Loaded image: 이름:태그"에서 이름만 뽑는다
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

# 네트워크와 설정 폴더는 처음 한 번만 만들어진다(이미 있으면 그냥 넘어간다)
docker network inspect "$NET" >/dev/null 2>&1 || docker network create "$NET" >/dev/null
mkdir -p "$PROXY_DIR"

# ---------- 3. 지금 상태 읽기 ----------

# 앱 컨테이너 수. 한 대씩 바꾸므로 2 이상이어야 배포 중에도 응답한다
REPLICAS="${REPLICAS:-2}"
if ! [[ "$REPLICAS" =~ ^[2-9]$ ]]; then
  echo "REPLICAS는 2~9여야 해요: $REPLICAS" >&2
  exit 1
fi
# 새 앱이 UP이 될 때까지 기다리는 최대 시간(초). DB 마이그레이션이 길면 늘린다
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-240}"

# 프록시가 지금 넘겨주는 앱들의 네트워크 이름(app-1, app-2, 예전 블루그린이면 app-blue 같은 것).
# 프록시가 없으면(블루그린 전 배포) 비어 있다
SERVING=()
PROXY_UP=""
if [ "$(docker inspect -f '{{.State.Running}}' "$PROXY" 2>/dev/null)" = "true" ] \
  && [ -f "$PROXY_DIR/default.conf" ]; then
  PROXY_UP="yes"
  # 설정 파일의 "server 앱이름:8080" 줄에서 앱 이름만 뽑아 배열에 담는다
  mapfile -t SERVING < <(grep -oE 'app-[a-z0-9]+:8080' "$PROXY_DIR/default.conf" | sed 's/:8080$//' | sort -u)
fi

# ---------- 4. 쓰는 함수들 ----------
# 네트워크 이름(app-1) → 컨테이너 이름(<계정>-blog-server-1)
container_of() { echo "${NAME}-${1#app-}"; }
# 프록시가 지금 그 앱에 넘겨주고 있으면 참
serving_has() {
  local h
  for h in "${SERVING[@]}"; do [ "$h" = "$1" ] && return 0; done
  return 1
}
# 지금 넘겨주는 앱 중에서 하나를 뺀 목록 (그 앱을 바꾸는 동안 남길 앱들)
without() {
  local h
  for h in "${SERVING[@]}"; do [ "$h" != "$1" ] && echo "$h"; done
}

# 서버 컨테이너는 포트를 열지 않고 프록시와 같은 네트워크에만 붙는다.
# 업로드 폴더는 볼륨 하나를 모든 앱이 같이 쓴다
# 옵션 뜻:
#   --restart unless-stopped  앱이 죽거나 서버가 다시 켜지면 docker가 다시 띄운다(손으로 멈춘 것은 빼고)
#   --network-alias           네트워크 안에서 app-1 같은 이름으로 찾게 한다. 계정 이름의 _가 들어간 이름은
#                             Tomcat이 Host로 받지 않아(400) 짧은 별명을 쓴다
#   --env-file                DB 주소, 비밀키 같은 환경변수를 app.env에서 읽는다
#   -v ...-uploads / -logs    올린 사진과 로그를 컨테이너가 바뀌어도 남는 docker 볼륨에 둔다
#   --log-opt                 docker 로그 파일이 서버 디스크를 채우지 않게 20MB씩 5개까지만
start() {
  local host="$1" cname
  cname="$(container_of "$host")"
  docker rm -f "$cname" >/dev/null 2>&1 || true   # 같은 이름의 멈춘 컨테이너가 남아 있으면 지운다
  docker run -d \
    --name "$cname" \
    --restart unless-stopped \
    --network "$NET" \
    --network-alias "$host" \
    --env-file "$APP_DIR/app.env" \
    -v "${OWNER}-blog-uploads:/app/uploads" \
    -v "${OWNER}-blog-logs:/app/logs" \
    --log-opt max-size=20m --log-opt max-file=5 \
    "$IMAGE:latest" >/dev/null
}

# 네트워크 안에서 그 컨테이너의 상태 확인 주소를 읽는다. 앱은 포트를 열지 않아서 서버에서 바로 못 부르고,
# 같은 네트워크에 잠깐 컨테이너를 띄워 묻는다(--rm이라 끝나면 지워진다). "UP"이 있으면 참
probe() {
  docker run --rm --network "$NET" "$PROXY_IMAGE" \
    wget -qO- -T 3 "http://$1:8080/actuator/health" 2>/dev/null | grep -q '"UP"'
}

# 확인 한 번에 컨테이너를 하나 띄우느라 몇 초씩 걸리므로 횟수가 아니라 시간(기본 4분, HEALTH_TIMEOUT 초)으로 끊는다.
# 컨테이너가 죽으면 기다리지 않는다
healthy() {
  local host="$1" cname deadline=$((SECONDS + HEALTH_TIMEOUT)) next_note=$((SECONDS + 20))
  cname="$(container_of "$host")"
  while [ "$SECONDS" -lt "$deadline" ]; do
    if [ "$(docker inspect -f '{{.State.Running}}' "$cname" 2>/dev/null)" != "true" ]; then
      return 1
    fi
    if probe "$host"; then
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

# 프록시가 넘겨줄 앱 목록으로 설정을 쓴다.
# - 같은 접속 IP는 늘 같은 앱으로 보낸다(서버 nginx가 넘긴 X-Real-IP·X-Forwarded-For로 hash).
#   메모리로 세는 IP별 요청 제한이 앱 수만큼 늘어나지 않게 한다
# - resolve: 컨테이너가 죽었다 다시 떠서 내부 주소가 바뀌어도 따라간다
# - 앱 하나가 연결을 거절하거나 502·503이면 다른 앱으로 한 번 더 보낸다
write_proxy_conf() {
  local servers="" h
  for h in "$@"; do
    servers+="    server ${h}:8080 resolve max_fails=1 fail_timeout=10s;"$'\n'
  done
  # 새 파일에 다 쓴 뒤 mv로 바꿔치기한다. 쓰는 도중의 반쪽 파일을 nginx가 읽지 않게 하려는 것
  # (아래 nginx 설정의 # 줄은 설명이다. nginx도 # 뒤를 읽지 않는다)
  cat > "$PROXY_DIR/default.conf.new" <<CONF
# docker 안의 DNS. 컨테이너 이름(별명)을 10초마다 다시 찾는다
resolver 127.0.0.11 valid=10s ipv6=off;

# 요청을 나눠 받을 앱 목록 (deploy.sh가 쓴다)
upstream app {
    # 앱 목록을 nginx 일꾼들이 같이 보는 메모리. resolve를 쓰려면 필요하다
    zone app 64k;
    # 접속한 사람 IP로 앱을 고른다. 같은 사람은 늘 같은 앱으로 간다
    hash \$http_x_real_ip\$http_x_forwarded_for\$remote_addr consistent;
${servers}}

server {
    # 컨테이너 안 8080. 서버의 <포트번호>가 여기로 이어진다
    listen 8080;
    # 사진 올리기 최대 크기
    client_max_body_size 20m;
    # 접속 기록은 서버 nginx가 남기므로 여기서는 끈다
    access_log off;

    location / {
        proxy_pass http://app;
        # 앱이 연결을 못 받거나 502·503이면 다른 앱으로 한 번 더 보낸다 (최대 2번 시도)
        proxy_next_upstream error timeout http_502 http_503;
        proxy_next_upstream_tries 2;
        # 서버 nginx가 넘긴 원래 주소·접속 IP·https 여부를 앱까지 그대로 전한다
        proxy_http_version 1.1;
        proxy_set_header Host              \$http_host;
        proxy_set_header X-Real-IP         \$http_x_real_ip;
        proxy_set_header X-Forwarded-For   \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$http_x_forwarded_proto;
        proxy_set_header Upgrade           \$http_upgrade;
        proxy_set_header Connection        \$http_connection;
        # 앱이 60초 안에 답하지 않으면 끊는다
        proxy_read_timeout 60s;
    }
}
CONF
  mv "$PROXY_DIR/default.conf.new" "$PROXY_DIR/default.conf"
}

# 설정을 쓰고 프록시가 다시 읽게 한다. 이미 처리 중인 요청은 옛 설정으로 끝까지 간다
route_to() {
  write_proxy_conf "$@"
  if ! docker exec "$PROXY" nginx -t >/dev/null 2>&1; then
    echo "== 프록시 설정이 잘못됐어요:" >&2
    docker exec "$PROXY" nginx -t >&2 || true
    exit 1
  fi
  docker exec "$PROXY" nginx -s reload >/dev/null
  SERVING=("$@")
}

# 사용자와 같은 길(서버 포트 → 프록시 → 앱)로 상태를 물어 UP인지 본다. 최대 15번(약 30초)
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

# 새 앱이 끝내 뜨지 않았을 때: 원인을 볼 수 있게 마지막 로그를 찍고 그 컨테이너만 지운다
fail_start() {
  local host="$1" cname
  cname="$(container_of "$host")"
  echo "== $cname 가 ${HEALTH_TIMEOUT}초 안에 뜨지 않았어요. 마지막 로그:" >&2
  docker logs --tail 80 "$cname" >&2 || true
  docker rm -f "$cname" >/dev/null 2>&1 || true
}

# 이번 배포가 끝났을 때 돌고 있어야 할 앱 이름들: app-1 … app-<REPLICAS>
HOSTS=()
for i in $(seq 1 "$REPLICAS"); do HOSTS+=("app-$i"); done

if [ -z "$PROXY_UP" ]; then
  # ---------- 5-가. 처음: 프록시가 없을 때 ----------
  # 처음 이 방식으로 바꿀 때: 예전 컨테이너가 포트를 잡고 있어 프록시를 띄우려면 한 번 잠깐 멈춘다
  echo "== 앱 ${REPLICAS}대 띄우기 (지금 응답 중: 이전 방식 컨테이너)"
  for h in "${HOSTS[@]}"; do
    start "$h"
    if ! healthy "$h"; then
      fail_start "$h"
      for x in "${HOSTS[@]}"; do docker rm -f "$(container_of "$x")" >/dev/null 2>&1 || true; done
      echo "== 새 컨테이너를 지웠어요. 지금까지 돌던 서버가 계속 응답해요. 원인을 고친 뒤 다시 배포해 주세요" >&2
      exit 1
    fi
  done
  write_proxy_conf "${HOSTS[@]}"
  docker rm -f "$NAME" "${NAME}-blue" "${NAME}-green" >/dev/null 2>&1 || true
  docker rm -f "$PROXY" >/dev/null 2>&1 || true
  # 프록시만 서버 포트를 연다(-p 서버포트:8080). 설정 폴더는 읽기 전용으로 붙인다
  docker run -d \
    --name "$PROXY" \
    --restart unless-stopped \
    --network "$NET" \
    -p "${PORT}:8080" \
    -v "$PROXY_DIR:/etc/nginx/conf.d:ro" \
    --log-opt max-size=10m --log-opt max-file=3 \
    "$PROXY_IMAGE" >/dev/null
else
  # ---------- 5-나. 평소: 한 대씩 바꾸기(롤링) ----------
  # 한 대씩 바꾼다. 프록시가 넘겨주지 않는 자리(새로 늘린 자리, 지난 배포에 실패한 자리)부터 채워서
  # 바꾸는 동안 늘 한 대 이상이 응답하게 한다
  ORDER=()
  for h in "${HOSTS[@]}"; do serving_has "$h" || ORDER+=("$h"); done
  for h in "${HOSTS[@]}"; do serving_has "$h" && ORDER+=("$h"); done

  for h in "${ORDER[@]}"; do
    cname="$(container_of "$h")"
    mapfile -t KEEP < <(without "$h")
    # ① 프록시에서 뺀다 → ② 처리 중인 요청을 30초까지 기다려 주고 멈춘다
    if serving_has "$h"; then
      echo "== $cname 를 프록시에서 빼고 내리기"
      route_to "${KEEP[@]}"
      docker stop -t 30 "$cname" >/dev/null 2>&1 || true
    fi
    # ③ 새 이미지로 띄우고 UP이 될 때까지 기다린다
    echo "== $cname 새 버전 띄우기. 지금 응답 중: ${KEEP[*]}"
    start "$h"
    if ! healthy "$h"; then
      fail_start "$h"
      echo "== $cname 만 지웠어요. ${KEEP[*]} 가 계속 응답해요. 원인을 고친 뒤 다시 배포해 주세요" >&2
      exit 1
    fi
    # ④ 다시 프록시에 넣는다. 다음 앱으로 넘어간다
    route_to "${KEEP[@]}" "$h"
  done

  # 예전 블루그린 컨테이너처럼 이제 쓰지 않는 자리는 프록시에서 빼고 내린다
  mapfile -t OLD < <(for h in "${SERVING[@]}"; do
    printf '%s\n' "${HOSTS[@]}" | grep -qx "$h" || echo "$h"
  done)
  if [ "${#OLD[@]}" -gt 0 ]; then
    echo "== 쓰지 않는 컨테이너 내리기: ${OLD[*]}"
    route_to "${HOSTS[@]}"
    for h in "${OLD[@]}"; do
      docker stop -t 30 "$(container_of "$h")" >/dev/null 2>&1 || true
    done
  fi
  # 설정에 없지만 남아 있는 예전 컨테이너
  for x in "$NAME" "${NAME}-blue" "${NAME}-green"; do
    docker rm -f "$x" >/dev/null 2>&1 || true
  done
fi

# ---------- 6. 마무리 확인과 정리 ----------
if ! through_proxy; then
  echo "== 프록시를 거쳐 $HEALTH_URL 가 UP이 아니에요. 프록시 로그:" >&2
  docker logs --tail 40 "$PROXY" >&2 || true
  exit 1
fi

echo "== 배포 완료: $HEALTH_URL 가 UP이에요 (${HOSTS[*]})"
rm -f "$APP_DIR/image.tar.gz"                     # 올라온 이미지 파일은 불러왔으니 지운다
docker image prune -f >/dev/null 2>&1 || true     # 이름표 없는 옛 이미지를 지워 디스크를 비운다(:previous는 남는다)
