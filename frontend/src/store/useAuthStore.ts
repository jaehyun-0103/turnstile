import { create } from "zustand";
import { persist, createJSONStorage } from "zustand/middleware";

// 상태 및 액션 타입 정의
interface AuthState {
  identityToken: string | null;
  queueToken: string | null;
  paymentToken: string | null;

  // 상태 변경 액션
  setIdentityToken: (token: string) => void;
  setQueueToken: (token: string) => void;
  setPaymentToken: (token: string) => void;

  // 초기화 액션
  clearAllTokens: () => void;
  clearCheckoutTokens: () => void; // 결제가 끝나거나 롤백될 때 예매/결제 권한만 초기화
}

// Zustand 스토어 생성
export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      // 초기 상태
      identityToken: null,
      queueToken: null,
      paymentToken: null,

      // 토큰 세팅
      setIdentityToken: (token) => set({ identityToken: token }),
      setQueueToken: (token) => set({ queueToken: token }),
      setPaymentToken: (token) => set({ paymentToken: token }),

      // 토큰 초기화
      clearAllTokens: () => set({ identityToken: null, queueToken: null, paymentToken: null }),
      clearCheckoutTokens: () => set({ queueToken: null, paymentToken: null }),
    }),
    {
      name: "turnstile-auth-storage", // 키 이름
      storage: createJSONStorage(() => sessionStorage),
    },
  ),
);
