import { Routes, Route, Navigate } from 'react-router-dom';
import { GNB } from "./components/GNB";
import { LoginPage } from "./pages/LoginPage";
import { MyPage } from "./pages/MyPage";
import { ConcertListPage } from "./pages/ConcertListPage";
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
            <Route path="/" element={<ConcertListPage />} />
            <Route path="/mypage" element={<MyPage />} />
          </Route>
        </Routes>
      </main>
    </div>
  );
}
export default App;
