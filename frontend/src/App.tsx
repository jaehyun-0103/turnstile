import { createBrowserRouter, RouterProvider, Outlet } from "react-router-dom";
import { GNB } from "./components/GNB";
import { LoginPage } from "./pages/LoginPage";
import { MyPage } from "./pages/MyPage";
import { ConcertListPage } from "./pages/ConcertListPage";
import { ProtectedRoute } from "./router/ProtectedRoute";
import { QueueWaitingPage } from "./pages/QueueWaitingPage";
import { SeatSelectionPage } from "./pages/SeatSelectionPage";
import { PaymentPage } from "./pages/PaymentPage";
import { Toaster } from "react-hot-toast";

// 레이아웃 컴포넌트
const RootLayout = () => {
  return (
    <div className="min-h-screen bg-gray-50 flex flex-col">
      <Toaster position="top-center" reverseOrder={false} />
      {/* 상단 공통 네비게이션 바 */}
      <GNB />

      {/* 메인 콘텐츠 라우팅 영역 */}
      <main className="flex-1">
        <Outlet />
      </main>
    </div>
  );
};

// 데이터 라우터 정의
const router = createBrowserRouter([
  {
    element: <RootLayout />, // 전체를 감싸는 껍데기
    children: [
      {
        path: "/login",
        element: <LoginPage />, // 퍼블릭
      },
      {
        element: <ProtectedRoute />, // 보호된 구역
        children: [
          { path: "/", element: <ConcertListPage /> },
          { path: "/mypage", element: <MyPage /> },
          { path: "/queue", element: <QueueWaitingPage /> },
          { path: "/seats", element: <SeatSelectionPage /> },
          { path: "/payment", element: <PaymentPage /> },
        ],
      },
    ],
  },
]);

// 최상위 라우팅 컴포넌트
function App() {
  return <RouterProvider router={router} />;
}

export default App;
