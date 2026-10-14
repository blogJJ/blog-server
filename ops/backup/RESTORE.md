# DB 복구 절차 (T139, OPS-01, D-101, D-121)

백업은 매일 03:00(Asia/Seoul)에 Backup 워크플로가 만든다. 같은 백업이 두 곳에 7일치 있다.

| 위치 | 형식 | 언제 쓰나 |
| --- | --- | --- |
| 저장소 Actions > Backup > 실행 기록 > Artifacts `db-blog-YYYYMMDD-HHMM` | 암호화된 `*.sql.gz.enc` | 서버 디스크가 망가졌을 때 |
| 서버 `~/blog-server/backups/blog-YYYYMMDD-HHMM.sql.gz` | 암호화 안 된 `*.sql.gz` (이 계정만 읽음) | 서버는 멀쩡하고 데이터만 되돌릴 때 |

복구하면 DB의 모든 테이블이 백업 시점으로 바뀐다. 그 뒤에 생긴 글·가입은 사라진다.

## 1. 백업 파일 준비

서버에 있는 백업을 쓰면 이 단계는 건너뛴다.

1. GitHub 저장소 > Actions > Backup에서 되돌릴 날짜의 실행을 열고 Artifacts의 `db-blog-…`를 받는다(zip).
2. 압축을 풀고 암호를 푼다. 암호는 Repository secret `BACKUP_PASSPHRASE`에 넣은 값이다.

   ```bash
   unzip db-blog-20261013-0300.zip
   openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 \
     -in blog-20261013-0300.sql.gz.enc -out blog-20261013-0300.sql.gz
   gzip -t blog-20261013-0300.sql.gz && echo OK
   ```

3. 서버로 올린다.

   ```bash
   scp -P <SSH 포트> blog-20261013-0300.sql.gz <계정>@<서버>:blog-server/backups/
   ```

## 2. 운영 서버에 복구

서버에 ssh로 들어가서 한다. `<owner>`는 서버 계정 이름(소문자)이다.

```bash
cd ~/blog-server
F=backups/blog-20261013-0300.sql.gz

# 복구하는 동안 새 글이 써지지 않게 앱 컨테이너(app-1, app-2)를 모두 멈춘다. 프록시는 그대로 둔다(그동안 502)
docker ps --format '{{.Names}}' | grep -E '^<owner>-blog-server-[0-9]+$' | xargs -r docker stop

# 백업에는 DROP TABLE IF EXISTS가 들어 있어 테이블을 통째로 바꾼다
gzip -dc "$F" | docker run --rm -i --env-file app.env mysql:8.4 \
  sh -c 'MYSQL_PWD="$DB_PASSWORD" exec mysql --default-character-set=utf8mb4 \
    -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USERNAME" "$DB_NAME"'

# 멈췄던 컨테이너를 다시 띄운다
docker ps -a --format '{{.Names}}' | grep -E '^<owner>-blog-server-[0-9]+$' | xargs -r docker start
```

## 3. 확인

```bash
curl -s http://127.0.0.1:<APP_PORT>/actuator/health   # {"status":"UP"...}
```

사이트에 들어가 최근 글과 로그인을 확인한다. 서버가 안 뜨면 `docker logs <owner>-blog-server-1`(또는 `-2`)에서 Flyway 오류를 본다.
백업보다 새 버전의 서버가 돌고 있으면 Flyway가 빠진 마이그레이션을 다시 적용하므로 따로 할 일은 없다.

## 복구 연습 (공개 전 한 번, 그 뒤로 가끔)

운영 DB를 건드리지 않고 내 컴퓨터의 빈 DB에 복구해 본다.

```bash
docker compose up -d                       # 로컬 MySQL (blog/blog)
gzip -dc blog-20261013-0300.sql.gz | docker exec -i -e MYSQL_PWD=blog blog-mysql \
  mysql --default-character-set=utf8mb4 -ublog blog
./gradlew bootRun --args='--spring.profiles.active=local --app.mascot.api-key='
curl -s http://localhost:8080/actuator/health  # UP이면 성공
```

연습이 끝나면 받은 백업 파일을 지운다. 회원 개인정보가 들어 있다.
