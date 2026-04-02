import { useState } from "react";
import { GNB } from "./components/GNB";
import { LoginPage } from "./pages/LoginPage";
import { MyPage } from "./pages/MyPage";

function App() {
  const [currentView, setCurrentView] = useState<"login" | "mypage">("login");
  return (
    <div className="min-h-screen bg-gray-50 flex flex-col">
      {/* 상단 공통 네비게이션 바 */}
      <GNB />

      {/* 디자인 확인용 임시 탭 버튼 (실제 운영 시에는 삭제) */}
      <div className="flex justify-center gap-4 p-4 bg-yellow-100 text-sm">
        <span className="font-bold">👀 화면 미리보기 탭:</span>
        <button className="underline text-blue-600" onClick={() => setCurrentView("login")}>
          로그인 화면
        </button>
        <button className="underline text-blue-600" onClick={() => setCurrentView("mypage")}>
          마이페이지 화면
        </button>
      </div>

      {/* 메인 콘텐츠 영역 */}
      <main className="flex-1">
        {currentView === "login" && <LoginPage />}
        {currentView === "mypage" && <MyPage />}
      </main>
    </div>
  );
}
export default App;
