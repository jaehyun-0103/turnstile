import { Navigate, Outlet } from "react-router-dom";
import { useAuthStore } from "../store/useAuthStore";

/**
 * 로그인(IDENTITY 토큰) 여부를 검사하여 비인가자의 접근을 차단하는 라우트 가드.
 * 인가된 유저는 하위 페이지(<Outlet />)로 통과시키고, 비인가자는 로그인 페이지로 리다이렉트합니다.
 */
export const ProtectedRoute = () => {
  const { identityToken } = useAuthStore();

  // 토큰이 없으면 조건 없이 /login 페이지로 강제 이동
  if (!identityToken) {
    return <Navigate to="/login" replace />;
  }

  // 토큰이 있다면 정상적으로 해당 페이지 렌더링
  return <Outlet />;
};
