# 초경량 Java 21 JRE 런타임 이미지 사용
FROM eclipse-temurin:21-jre-alpine

# 컨테이너 내부 작업 디렉토리 설정
WORKDIR /app

# 호스트에서 빌드된 Jar 파일을 컨테이너로 복사
ARG JAR_FILE=build/libs/*SNAPSHOT.jar
COPY ${JAR_FILE} app.jar

# 애플리케이션 실행
ENTRYPOINT ["java", "-Duser.timezone=Asia/Seoul", "-jar", "app.jar"]