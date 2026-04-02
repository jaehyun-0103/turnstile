import { Routes, Route, Navigate } from 'react-router-dom';
import { GNB } from "./components/GNB";
import { LoginPage } from "./pages/LoginPage";
import { MyPage } from "./pages/MyPage";
import { ProtectedRoute } from './router/ProtectedRoute';

function App() {
  return (
    <div className="min-h-screen bg-gray-50 flex flex-col">
      {/* 상단 공통 네비게이션 바 */}
      <GNB />

      {/* 메인 콘텐츠 영역 */}
      <main className="flex-1">
        <Routes>
          {/* 누구나 접근 가능한 영역 (Public) */}
          <Route path="/login" element={<LoginPage />} />

          {/* 로그인한 유저만 접근 가능한 영역 (Protected) */}
          <Route element={<ProtectedRoute />}>
            {/* TODO: 나중에 콘서트 목록 페이지가 생기면 path="/" 를 거기로 연결합니다. 
                지금은 메인 접속 시 마이페이지로 이동하게 해두었습니다. */}
            <Route path="/" element={<Navigate to="/mypage" replace />} />
            <Route path="/mypage" element={<MyPage />} />
          </Route>
        </Routes>
      </main>
    </div>
  );
}
export default App;
