import { useNavigate } from "react-router-dom";
import { useAuthStore } from "../store/useAuthStore";

export const GNB = () => {
  const navigate = useNavigate();
  const { userInfo, clearAllTokens } = useAuthStore();

  // 로그아웃 동작 함수
  const handleLogout = () => {
    clearAllTokens();
    navigate("/login");
  };

  return (
    <header className="sticky top-0 z-50 w-full bg-white border-b border-gray-200 shadow-sm">
      <div className="max-w-7xl mx-auto px-4 h-16 flex items-center justify-between">
        <div className="text-2xl font-extrabold text-blue-600 cursor-pointer" onClick={() => navigate("/")}>
          Turnstile
        </div>

        <div>
          {userInfo ? (
            <div className="flex items-center gap-6">
              <div className="text-sm text-gray-600">
                <span className="font-semibold text-gray-800">{userInfo.name}</span> 님
                <span className="ml-4 text-blue-600 font-bold">{userInfo.balance.toLocaleString()} 원</span>
              </div>

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
