# 운영 서버 이미지. 먼저 ./gradlew bootJar로 build/libs/blog-server.jar를 만든 뒤 빌드한다.
# 배포는 .github/workflows/deploy.yml이 하고, 서버에서는 deploy/deploy.sh가 이 이미지를 띄운다.
# 바탕 이미지: Java 21 실행 환경만 든 것(JDK보다 작다)
FROM eclipse-temurin:21-jre

# 기본 환경변수
#   TZ, user.timezone        로그와 시간 계산을 한국 시간으로
#   SPRING_PROFILES_ACTIVE   운영 설정(application-prod.yml)을 쓴다
#   STORAGE_LOCAL_DIR        올린 사진을 두는 곳. deploy.sh가 docker 볼륨을 여기에 붙인다
#   LOG_PATH                 로그 파일 폴더. 이것도 볼륨
#   MaxRAMPercentage=75      컨테이너 메모리의 75%까지 자바가 쓴다
ENV TZ=Asia/Seoul \
    SPRING_PROFILES_ACTIVE=prod \
    STORAGE_LOCAL_DIR=/app/uploads \
    LOG_PATH=/app/logs \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Seoul"

# root가 아닌 계정으로 실행한다. 올린 사진과 로그는 docker 볼륨(/app/uploads, /app/logs)에 남는다
RUN groupadd --system app && useradd --system --gid app --home-dir /app app \
    && mkdir -p /app/uploads /app/logs && chown -R app:app /app
WORKDIR /app
# 빌드한 jar를 넣고, 이후 명령은 app 계정으로 실행한다
COPY --chown=app:app build/libs/blog-server.jar app.jar
USER app

# 앱이 듣는 포트(컨테이너 안). 밖으로 여는 일은 deploy.sh의 프록시가 한다
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
