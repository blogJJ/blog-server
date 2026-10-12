# 운영 서버 이미지. 먼저 ./gradlew bootJar로 build/libs/blog-server.jar를 만든 뒤 빌드한다.
# 배포는 .github/workflows/deploy.yml이 하고, 서버에서는 deploy/deploy.sh가 이 이미지를 띄운다.
FROM eclipse-temurin:21-jre

ENV TZ=Asia/Seoul \
    SPRING_PROFILES_ACTIVE=prod \
    STORAGE_LOCAL_DIR=/app/uploads \
    LOG_PATH=/app/logs \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Seoul"

# root가 아닌 계정으로 실행한다. 올린 사진과 로그는 docker 볼륨(/app/uploads, /app/logs)에 남는다
RUN groupadd --system app && useradd --system --gid app --home-dir /app app \
    && mkdir -p /app/uploads /app/logs && chown -R app:app /app
WORKDIR /app
COPY --chown=app:app build/libs/blog-server.jar app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
