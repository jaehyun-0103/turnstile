import { create } from "zustand";
import { persist, createJSONStorage } from "zustand/middleware";

interface UserInfo {
  userId: number;
  username: string;
  name: string;
  balance: number;
}

// 백엔드의 3-Tier 인가 정책과 유저 정보를 프론트엔드에 동기화하는 전역 스토어
interface AuthState {
  identityToken: string | null;
  queueToken: string | null;
  paymentToken: string | null;
  userInfo: UserInfo | null;

  setIdentityToken: (token: string) => void;
  setQueueToken: (token: string) => void;
  setPaymentToken: (token: string) => void;
  setUserInfo: (user: UserInfo) => void;
  updateBalance: (newBalance: number) => void;

  clearAllTokens: () => void;
  // 예매 완료 또는 취소(롤백) 시 기본 로그인 상태는 유지하고 예매/결제용 고권한 토큰만 회수하여 세션을 보호
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

      clearAllTokens: () =>
        set({
          identityToken: null,
          queueToken: null,
          paymentToken: null,
          userInfo: null,
        }),
      clearCheckoutTokens: () => set({ queueToken: null, paymentToken: null }),
    }),
    {
      name: "turnstile-auth-storage",
      // 새로고침 시 대기열 튕김을 방지하되 브라우저 탭 종료 시 데이터가 휘발
      storage: createJSONStorage(() => sessionStorage),
    },
  ),
);
