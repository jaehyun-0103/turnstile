import axios from "axios";
import { useAuthStore } from "../store/useAuthStore";
import toast from "react-hot-toast";

/**
 * 전역 API 클라이언트입니다.
 * 백엔드의 3-Tier 하이브리드 토큰 정책에 맞춰 엔드포인트별로 적절한 권한을 동적으로 주입하고,
 * 응답 에러(401 등) 발생 시 권한 수준에 맞는 세션 롤백 처리를 수행합니다.
 */
export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "http://localhost:8080",
  timeout: 5000,
  headers: {
    "Content-Type": "application/json",
  },
});

// API 호출 전 URL을 분석하여 알맞은 계층의 토큰 주입
apiClient.interceptors.request.use(
  (config) => {
    const url = config.url || "";
    const { identityToken, queueToken, paymentToken } = useAuthStore.getState();

    let tokenToInject: string | null = null;

    // 백엔드 AuthInterceptor 정책에 따른 3-Tier 토큰 선별 로직
    if (url.startsWith("/api/v1/payments")) {
      // LEVEL 3
      tokenToInject = paymentToken || queueToken || identityToken;
    } else if (url === "/api/v1/reservations/my") {
      // LEVEL 1
      tokenToInject = identityToken;
    } else if (url.startsWith("/api/v1/reservations") || url.startsWith("/api/v1/schedules")) {
      // LEVEL 2
      tokenToInject = queueToken || identityToken;
    } else {
      // LEVEL 1
      tokenToInject = identityToken;
    }

    if (tokenToInject) {
      config.headers.Authorization = `Bearer ${tokenToInject}`;
    }

    return config;
  },
  (error) => Promise.reject(error),
);

// 백엔드의 인가 실패(401) 응답에 대한 클라이언트 방어 로직
apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response) {
      const status = error.response.status;
      const url = error.config.url || "";

      if (status === 401) {
        // 예매/결제 도메인에서 발생한 단기 토큰 만료인 경우
        if (url.startsWith("/api/v1/reservations") || url.startsWith("/api/v1/payments") || url.startsWith("/api/v1/schedules")) {
          console.warn(`[Network] Interceptor | 401 Unauthorized - 예매/결제 단기 토큰 만료 처리 (URL: ${url})`);
          // 전체 로그아웃을 방지하고 해당 1회성/단기 토큰만 안전하게 파기
          useAuthStore.getState().clearCheckoutTokens();
        } else {
          console.warn(`[Network] Interceptor | 401 Unauthorized - 메인 세션 만료, 강제 로그아웃 (URL: ${url})`);
          // 기본 로그인(IDENTITY) 토큰 자체가 만료된 경우에만 전체 세션 초기화 및 로그인 화면으로 강제 이동
          useAuthStore.getState().clearAllTokens();
          window.location.href = "/login";
          toast.error("세션이 만료되었습니다. 다시 로그인해주세요.");
        }
      }
    }
    return Promise.reject(error);
  },
);
