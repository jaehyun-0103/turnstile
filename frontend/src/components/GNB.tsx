import { useNavigate } from "react-router-dom";
import { useAuthStore } from "../store/useAuthStore";

/**
 * 애플리케이션 최상단에 고정되는 글로벌 네비게이션 바(GNB)입니다.
 * 유저의 로그인 상태와 실시간 잔액을 표시하고 공통 라우팅 및 로그아웃을 담당합니다.
 */
export const GNB = () => {
  const navigate = useNavigate();
  const { userInfo, clearAllTokens } = useAuthStore();

  // 모든 토큰 파기 후 로그인 화면으로 이동 (로그아웃)
  const handleLogout = () => {
    clearAllTokens();
    navigate("/login");
  };

  return (
    <header className="sticky top-0 z-50 w-full bg-white border-b border-gray-200 shadow-sm">
      <div className="max-w-7xl mx-auto px-4 h-16 flex items-center justify-between">
        {/* 로고 영역 (클릭 시 메인 홈으로 이동) */}
        <div className="text-2xl font-extrabold text-blue-600 cursor-pointer" onClick={() => navigate("/")}>
          Turnstile
        </div>

        {/* 우측 유저 컨트롤 영역 */}
        <div>
          {userInfo ? (
            <div className="flex items-center gap-6">
              {/* 내 정보 및 실시간 잔액 */}
              <div className="text-sm text-gray-600">
                <span className="font-semibold text-gray-800">{userInfo.name}</span> 님
                <span className="ml-4 text-blue-600 font-bold">{userInfo.balance.toLocaleString()} 원</span>
              </div>

              {/* 페이지 이동 버튼들 */}
              <button
                onClick={() => navigate("/mypage")}
                className="text-sm font-bold text-blue-600 bg-blue-50 px-3 py-1.5 rounded-lg hover:bg-blue-100 transition"
              >
                마이페이지
              </button>

              <button onClick={handleLogout} className="text-sm font-medium text-gray-500 hover:text-gray-800 transition">
                로그아웃
              </button>
            </div>
          ) : (
            // 비로그인 상태일 때 노출
            <button
              onClick={() => navigate("/login")}
              className="px-4 py-2 text-sm font-semibold text-white bg-blue-600 rounded-lg hover:bg-blue-700 transition"
            >
              로그인
            </button>
          )}
        </div>
      </div>
    </header>
  );
};
