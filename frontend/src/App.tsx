import { Routes, Route, Navigate } from 'react-router-dom';
import { GNB } from "./components/GNB";
import { LoginPage } from "./pages/LoginPage";
import { MyPage } from "./pages/MyPage";
import { ConcertListPage } from "./pages/ConcertListPage";
import { ProtectedRoute } from './router/ProtectedRoute';
import { QueueWaitingPage } from "./pages/QueueWaitingPage";

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
            {/* 인증된 사용자의 기본 진입점을 콘서트 목록으로 설정하여 예매 동선을 최적화함 */}
            <Route path="/" element={<ConcertListPage />} />
            <Route path="/mypage" element={<MyPage />} />
            <Route path="/queue" element={<QueueWaitingPage />} />

            {/* 후속 이슈에서 구현될 좌석 선택 페이지의 라우트 충돌을 방지하기 위한 임시 렌더링 영역 */}
            <Route
              path="/seats"
              element={
                <div className="flex items-center justify-center h-[80vh]">
                  <div className="p-10 bg-white rounded-2xl shadow-sm border border-gray-100 text-center text-xl font-bold text-gray-700">
                    좌석 선택 페이지 (개발 예정)
                  </div>
                </div>
              }
            />
          </Route>

          {/* 정의되지 않은 잘못된 경로 접근 시 기본 화면으로 강제 리다이렉트하여 404 이탈을 방어함 */}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </div>
  );
}
export default App;
