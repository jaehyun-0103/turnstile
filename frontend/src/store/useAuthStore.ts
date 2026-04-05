import { create } from "zustand";
import { persist, createJSONStorage } from "zustand/middleware";

interface UserInfo {
  userId: number;
  username: string;
  name: string;
  balance: number;
}

/**
 * 백엔드의 3-Tier 하이브리드 인가 정책(IDENTITY, QUEUE, PAYMENT)과
 * 유저 기본 정보를 클라이언트 전역에서 동기화하는 Zustand 스토어입니다.
 */
interface AuthState {
  // 3-Tier 토큰 계층
  identityToken: string | null; // LEVEL 1: 로그인 및 기본 API 호출용
  queueToken: string | null; // LEVEL 2: 대기열 통과 및 좌석 조회/점유용
  paymentToken: string | null; // LEVEL 3: 좌석 점유 성공 후 1회성 결제용

  userInfo: UserInfo | null;

  // 상태 업데이트 메서드
  setIdentityToken: (token: string) => void;
  setQueueToken: (token: string) => void;
  setPaymentToken: (token: string) => void;
  setUserInfo: (user: UserInfo) => void;
  updateBalance: (newBalance: number) => void;

  // 세션 제어 메서드
  clearAllTokens: () => void;
  clearCheckoutTokens: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      identityToken: null,
      queueToken: null,
      paymentToken: null,
      userInfo: null,

      setIdentityToken: (token) => set({ identityToken: token }),
      setQueueToken: (token) => set({ queueToken: token }),
      setPaymentToken: (token) => set({ paymentToken: token }),
      setUserInfo: (user) => set({ userInfo: user }),
      updateBalance: (newBalance) =>
        set((state) => ({
          userInfo: state.userInfo ? { ...state.userInfo, balance: newBalance } : null,
        })),

      // 로그아웃 시 모든 권한과 정보를 초기화
      clearAllTokens: () =>
        set({
          identityToken: null,
          queueToken: null,
          paymentToken: null,
          userInfo: null,
        }),

      clearCheckoutTokens: () => set({ paymentToken: null }),
    }),
    {
      name: "turnstile-auth-storage",
      // 브라우저 종료 시 토큰이 휘발되도록 sessionStorage를 사용하여 보안 강화
      storage: createJSONStorage(() => sessionStorage),
    },
  ),
);
