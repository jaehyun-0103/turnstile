import axios from "axios";
import { useAuthStore } from "../store/useAuthStore";

/**
 * 백엔드의 3-Tier 하이브리드 토큰 정책(IDENTITY, QUEUE, PAYMENT)을 프론트엔드 통신 계층에 동기화하는 전역 API 클라이언트.
 * 엔드포인트에 따른 토큰 동적 주입 및 전역 인가 에러(401, 403)의 Fail-Fast 처리를 담당한다.
 */
export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "http://localhost:8080",
  // 백엔드의 Fail-Fast 정책에 맞춰 타임아웃을 짧게 설정하여 프론트엔드 단의 불필요한 커넥션 대기를 방지함
  timeout: 5000,
  headers: {
    "Content-Type": "application/json",
  },
});

apiClient.interceptors.request.use(
  (config) => {
    const url = config.url || "";

    // Axios 인터셉터는 React 생명주기 외부에서 동작하므로 상태 참조 시 훅 호출 대신 getState()를 사용하여 순환 참조 에러를 구조적으로 차단함
    const { identityToken, queueToken, paymentToken } = useAuthStore.getState();

    let tokenToInject: string | null = null;

    // 백엔드 AuthInterceptor.java의 URI 기반 3-Tier 인가 정책과 1:1로 대응하여, 목적에 맞는 최소 권한의 토큰만 선별하여 전송함
    if (url.startsWith("/api/v1/payments")) {
      tokenToInject = paymentToken;
    } else if (url.startsWith("/api/v1/reservations") || url.startsWith("/api/v1/schedules")) {
      tokenToInject = queueToken;
    } else {
      tokenToInject = identityToken;
    }

    if (tokenToInject) {
      config.headers.Authorization = `Bearer ${tokenToInject}`;
    }

    return config;
  },
  (error) => Promise.reject(error),
);

apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response) {
      const status = error.response.status;
      const errorCode = error.response.data?.code;
      const errorMessage = error.response.data?.message;

      if (status === 401) {
        console.warn("[API Error] 401 Unauthorized:", errorMessage);

        // 물리적 만료(A002) 및 위변조(A001) 토큰 감지 시 즉각적인 권한 회수 및 세션 초기화를 강제하여 좀비 세션을 방어함
        if (errorCode === "A001" || errorCode === "A002") {
          alert("인증이 만료되었습니다. 다시 로그인해주세요.");
          useAuthStore.getState().clearAllTokens();
          window.location.href = "/login";
        }
      } else if (status === 403) {
        console.warn("[API Error] 403 Forbidden:", errorMessage);
        alert(errorMessage || "해당 기능에 접근할 권한이 없습니다.");
      }
    }

    return Promise.reject(error);
  },
);
