# --- 1단계: 빌드 환경 (Node) ---
FROM node:20-alpine as builder

WORKDIR /app

# 패키지 매니저 파일 복사
COPY package.json package-lock.json ./
RUN npm ci

# 소스 코드 복사 및 빌드
COPY . .
RUN npm run build

# --- 2단계: 실행 환경 (Nginx) ---
FROM nginx:alpine

# Nginx 기본 설정 삭제
RUN rm /etc/nginx/conf.d/default.conf

# 커스텀 Nginx 설정 파일 복사
COPY nginx.conf /etc/nginx/conf.d/

# 1단계에서 빌드된 정적 파일들을 Nginx 호스팅 폴더로 복사
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]