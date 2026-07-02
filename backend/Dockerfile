# --- 1단계: 빌드 환경 (JDK 21) ---
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Gradle Wrapper 및 설정 파일 복사
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./

# 실행 권한 부여
RUN chmod +x ./gradlew

# 소스 코드 복사
COPY src src

# 애플리케이션 빌드 (테스트 제외)
RUN ./gradlew bootJar -x test --no-daemon

# --- 2단계: 실행 환경 (JRE 21) ---
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# builder에서 빌드된 JAR 파일 복사
COPY --from=builder /app/build/libs/*SNAPSHOT.jar app.jar

# 시간대 설정 및 애플리케이션 실행
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Duser.timezone=Asia/Seoul", "-jar", "app.jar"]