#!/usr/bin/env bash
# 서버에서 새 이미지를 띄운다. .github/workflows/deploy.yml이 이 파일, 이미지, 환경변수 파일을
# ~/blog-server/에 올린 뒤 ssh로 실행한다. 직접 실행할 때도 같다:
#
#   bash ~/blog-server/deploy.sh <포트번호>
#
# - 이미지: ~/blog-server/image.tar.gz (docker save | gzip)
# - 환경변수: ~/blog-server/app.env (KEY=값 한 줄씩, 따옴표 없이). 이 파일은 본인만 읽게 둔다
# - 컨테이너 안 8080을 서버의 <포트번호>로 연다. nginx가 이 포트로 넘겨준다
# - 새 컨테이너가 2분 안에 /actuator/health에서 UP이 안 되면 마지막으로 성공한 이미지와 환경변수로 되돌린다
# - 공용 서버라 컨테이너·이미지·볼륨 이름 앞에 접속 계정 이름을 붙여 다른 사람 것과 겹치지 않게 한다
set -euo pipefail

PORT="${1:?포트번호를 넣어 주세요. 예: bash deploy.sh 8300}"
if ! [[ "$PORT" =~ ^[0-9]{2,5}$ ]]; then
  echo "포트번호는 숫자여야 해요: $PORT" >&2
  exit 1
fi

APP_DIR="${APP_DIR:-$HOME/blog-server}"
OWNER="$(id -un | tr 'A-Z' 'a-z' | tr -c 'a-zA-Z0-9_.\n-' '-')"
NAME="${OWNER}-blog-server"
IMAGE="${OWNER}/blog-server"
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
# 지금 돌고 있는 이미지는 :previous로 남겨 두었다가 실패하면 되돌린다
if docker image inspect "$IMAGE:latest" >/dev/null 2>&1; then
  docker tag "$IMAGE:latest" "$IMAGE:previous"
fi
docker tag "$LOADED" "$IMAGE:latest"

# $1 이미지, $2 환경변수 파일
start() {
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  docker run -d \
    --name "$NAME" \
    --restart unless-stopped \
    --env-file "$2" \
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
  else
    echo "== 이전 버전도 뜨지 않았어요" >&2
  fi
fi
exit 1
