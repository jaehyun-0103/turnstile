import { Navigate, Outlet } from "react-router-dom";
import { useAuthStore } from "../store/useAuthStore";

/**
 * 프론트엔드 라우트 가드(Route Guard) 컴포넌트입니다.
 * LEVEL 1 (IDENTITY 토큰) 보유 여부를 검사하여 비인가자의 보호된 페이지 접근을 클라이언트 단에서 1차 차단합니다.
 */
export const ProtectedRoute = () => {
  const { identityToken } = useAuthStore();

  // 로그인 토큰이 없으면 조건 없이 /login 페이지로 강제 리다이렉트
  if (!identityToken) {
    return <Navigate to="/login" replace />;
  }

  // 인가된 유저는 하위 페이지(children route)로 통과
  return <Outlet />;
};
