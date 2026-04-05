import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { login } from "../apis/auth.api";
import { useAuthStore } from "../store/useAuthStore";

export const LoginPage = () => {
  const [username, setUsername] = useState("");
  const [isLoading, setIsLoading] = useState(false);

  const navigate = useNavigate();
  const { setIdentityToken, setUserInfo } = useAuthStore();

  const handleLogin = async (e: FormEvent) => {
    e.preventDefault();
    if (!username.trim()) return alert("아이디를 입력해주세요.");

    try {
      setIsLoading(true);
      // 백엔드 API 호출
      const response = await login({ username });

      // Zustand 스토어에 토큰 및 유저 정보 저장 (SessionStorage 자동 연동)
      setIdentityToken(response.accessToken);
      setUserInfo({
        userId: response.userId,
        username: response.username,
        name: response.name,
        balance: response.balance,
      });

      // 로그인 성공 시 메인 화면(콘서트 목록)으로 이동
      navigate("/");
    } catch (error) {
      console.error("로그인 실패:", error);
      alert("로그인에 실패했습니다. 아이디를 확인해주세요.");
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="flex items-center justify-center min-h-screen bg-gray-50">
      <div className="w-full max-w-md p-8 bg-white rounded-2xl shadow-lg">
        <h1 className="text-3xl font-bold text-center text-blue-600 mb-2">Turnstile</h1>
        <p className="text-center text-gray-500 mb-8">대규모 트래픽 제어 예매 시스템</p>

        <form onSubmit={handleLogin} className="flex flex-col gap-4">
          <div>
            <label htmlFor="username" className="block text-sm font-medium text-gray-700 mb-1">
              아이디 (Mock ID)
            </label>
            <input
              id="username"
              type="text"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              placeholder="예: user1"
              className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition"
            />
          </div>

          <button
            type="submit"
            disabled={isLoading}
            className={`w-full py-3 text-white font-semibold rounded-lg transition ${
              isLoading ? "bg-blue-400 cursor-not-allowed" : "bg-blue-600 hover:bg-blue-700"
            }`}
          >
            {isLoading ? "로그인 중..." : "로그인"}
          </button>
        </form>
      </div>
    </div>
  );
};
