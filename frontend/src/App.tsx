import { Routes, Route } from "react-router-dom";
import { GNB } from "./components/GNB";
import { LoginPage } from "./pages/LoginPage";
import { MyPage } from "./pages/MyPage";
import { ConcertListPage } from "./pages/ConcertListPage";
import { ProtectedRoute } from './router/ProtectedRoute';
import { QueueWaitingPage } from "./pages/QueueWaitingPage";
import { SeatSelectionPage } from "./pages/SeatSelectionPage";
import { PaymentPage } from "./pages/PaymentPage";
import { Toaster } from "react-hot-toast";

/**
 * 프론트엔드의 최상위 라우팅(Root Routing) 컴포넌트입니다.
 * 퍼블릭 라우트(로그인)와 보호된 라우트(예매 메인 플로우)를 분리하여 권한 제어를 적용합니다.
 */
function App() {
  return (
    <div className="min-h-screen bg-gray-50 flex flex-col">
      <Toaster position="top-center" reverseOrder={false} />
      {/* 상단 공통 네비게이션 바 (어느 페이지에서든 고정 노출) */}
      <GNB />

      {/* 메인 콘텐츠 라우팅 영역 */}
      <main className="flex-1">
        <Routes>
          {/* 퍼블릭 (비인가자 접근 가능) */}
          <Route path="/login" element={<LoginPage />} />

          {/* 보호된 구역 (Level 1 - IDENTITY 토큰 보유자만 접근 가능) */}
          <Route element={<ProtectedRoute />}>
            {/* 기본 진입점: 예매 동선 최적화를 위해 메인을 콘서트 목록으로 설정 */}
            <Route path="/" element={<ConcertListPage />} />
            <Route path="/mypage" element={<MyPage />} />

            {/* 핵심 예매 플로우 (진입 -> 대기열 -> 좌석선택 -> 결제) */}
            <Route path="/queue" element={<QueueWaitingPage />} />
            <Route path="/seats" element={<SeatSelectionPage />} />
            <Route path="/payment" element={<PaymentPage />} />
          </Route>
        </Routes>
      </main>
    </div>
  );
}

export default App;
