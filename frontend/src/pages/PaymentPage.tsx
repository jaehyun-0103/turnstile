import { useEffect, useState, useCallback } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { getMyBalance } from "../apis/user.api";
import { confirmPayment } from "../apis/payment.api";
import { useAuthStore } from "../store/useAuthStore";
import { isAxiosError } from "axios";

/**
 * 임시 점유된 예약 건의 최종 결제를 처리하는 페이지 컴포넌트입니다.
 * 백엔드의 좌석 점유 TTL(5분 30초)과 동기화된 타이머를 운용하며, 초과 시 예약을 파기합니다.
 */
export const PaymentPage = () => {
  const location = useLocation();
  const navigate = useNavigate();
  const { userInfo, updateBalance, clearCheckoutTokens } = useAuthStore();

  const state = location.state as { groupId?: string; expiredAt?: string; totalAmount?: number; concertId?: number } | null;
  const groupId = state?.groupId;
  const expiredAt = state?.expiredAt;
  const totalAmount = state?.totalAmount || 0;
  const concertId = state?.concertId;

  // 타이머 및 상태 관리
  const [timeLeft, setTimeLeft] = useState("05:30");
  const [isExpired, setIsExpired] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const [isPaying, setIsPaying] = useState(false);

  // 현재 잔액 동기화
  const fetchBalance = useCallback(async () => {
    try {
      const res = await getMyBalance();
      updateBalance(res.balance);
    } catch (error) {
      console.error("잔액 조회 실패:", error);
      alert("잔액 정보를 불러오지 못했습니다.");
    } finally {
      setIsLoading(false);
    }
  }, [updateBalance]);

  // 백엔드 점유 만료 시간(expiredAt) 기반 타이머 작동
  useEffect(() => {
    // 필수 데이터 누락 시 이전 화면으로 방어 라우팅
    if (!groupId || !expiredAt || !concertId) {
      alert("비정상적인 접근입니다.");
      navigate("/");
      return;
    }

    fetchBalance();

    const targetTime = new Date(expiredAt).getTime();

    // 1초마다 남은 시간 계산
    const updateTimer = () => {
      const now = Date.now();
      const diff = targetTime - now;

      if (diff <= 0) {
        setIsExpired(true);
        setTimeLeft("00:00");
        return true;
      }

      const minutes = Math.floor((diff / 1000 / 60) % 60);
      const seconds = Math.floor((diff / 1000) % 60);

      setTimeLeft(`${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`);
      return false;
    };

    const isAlreadyExpired = updateTimer();
    if (isAlreadyExpired) return;

    const timerId = setInterval(() => {
      const expired = updateTimer();
      if (expired) {
        console.warn("[Payment] Timer | Expired - 결제 제한 시간 만료로 인한 세션 롤백");
        clearInterval(timerId);
        alert("결제 시간이 만료되어 예매가 취소되었습니다.");

        // 시간 만료 시 1회성 결제 토큰 즉시 폐기 및 롤백
        clearCheckoutTokens();
        navigate("/");
      }
    }, 1000);

    return () => clearInterval(timerId);
  }, [groupId, expiredAt, concertId, navigate, fetchBalance, clearCheckoutTokens]);

  // 최종 결제 확정 핸들러
  const handlePayment = async () => {
    if (!groupId || !concertId) return;

    try {
      setIsPaying(true);
      // PAYMENT 토큰을 사용하여 백엔드 결제 검증 API 호출
      const res = await confirmPayment({
        concertId,
        groupId,
        amount: totalAmount,
      });

      console.log("[Payment] Confirm | Success - 결제 확정 및 좌석 SOLD 처리 완료");
      // 결제 성공 시 잔액 동기화 및 보안을 위한 토큰 즉각 회수
      updateBalance(res.balance);
      clearCheckoutTokens();

      alert("결제가 성공적으로 완료되었습니다!");
      navigate("/mypage");
    } catch (error) {
      if (isAxiosError(error) && error.response) {
        const errorCode = error.response.data?.code;
        const errorMessage = error.response.data?.message;

        // 잔액 부족, 중복 결제 등 백엔드 비즈니스 예외 세분화 처리
        if (errorCode === "S002") {
          console.warn("[Payment] Confirm | Reject - 잔액 부족 (S002)");
          alert("잔액이 부족합니다. 결제가 취소되었습니다.");
        } else {
          console.error(`[Payment] Confirm | Error - ${errorMessage} (${errorCode})`);
          alert(errorMessage || "결제 중 오류가 발생하여 예매가 취소되었습니다.");
        }
      } else {
        console.error("[Payment] Confirm | Error - 네트워크/알 수 없는 오류", error);
        alert("네트워크 오류가 발생했습니다.");
      }

      // 예외 발생 시 보안상 결제 토큰 회수 후 메인으로 강제 롤백 (Fail-Fast)
      clearCheckoutTokens();
      navigate("/");
    } finally {
      setIsPaying(false);
    }
  };

  const currentBalance = userInfo?.balance ?? 0;
  const isBalanceSufficient = currentBalance >= totalAmount;

  if (isLoading) {
    return <div className="flex justify-center items-center min-h-[80vh]">결제 정보를 불러오는 중...</div>;
  }

  return (
    <div className="max-w-2xl mx-auto py-12 px-4">
      <div className="bg-white rounded-3xl shadow-sm border border-gray-100 overflow-hidden">
        {/* 상단: 타이머 및 안내 영역 */}
        <div className="bg-blue-600 p-8 text-center text-white">
          <h1 className="text-2xl font-bold mb-2">결제 대기 중</h1>
          <p className="text-blue-100 mb-6">아래 시간 내에 결제를 완료하지 않으면 좌석이 취소됩니다.</p>
          <div className="inline-block bg-white/20 px-6 py-3 rounded-2xl backdrop-blur-sm border border-white/30">
            <span className="text-4xl font-mono font-bold tracking-wider">{timeLeft}</span>
          </div>
        </div>

        {/* 하단: 결제 상세 내역 및 버튼 영역 */}
        <div className="p-8">
          <h2 className="text-lg font-bold text-gray-800 mb-4">결제 상세 내역</h2>

          <div className="bg-gray-50 rounded-xl p-6 mb-8 flex flex-col gap-4">
            <div className="flex justify-between items-center pb-4 border-b border-gray-200">
              <span className="text-gray-600">총 결제 금액</span>
              <span className="text-xl font-bold text-gray-900">{totalAmount.toLocaleString()} 원</span>
            </div>
            <div className="flex justify-between items-center pt-2">
              <span className="text-gray-600">내 현재 잔액</span>
              <span className={`text-lg font-bold ${isBalanceSufficient ? "text-blue-600" : "text-red-500"}`}>
                {currentBalance.toLocaleString()} 원
              </span>
            </div>

            {!isBalanceSufficient && (
              <div className="mt-2 p-3 bg-red-50 border border-red-100 rounded-lg text-red-600 text-sm font-medium text-center">
                잔액이 부족합니다. 마이페이지에서 충전 후 다시 시도해주세요.
              </div>
            )}
          </div>

          <button
            onClick={handlePayment}
            disabled={isExpired || !isBalanceSufficient || isPaying}
            className={`w-full py-4 rounded-xl font-bold text-lg transition ${
              isExpired || !isBalanceSufficient || isPaying
                ? "bg-gray-200 text-gray-400 cursor-not-allowed"
                : "bg-blue-600 text-white hover:bg-blue-700 shadow-md"
            }`}
          >
            {isPaying ? "결제 처리 중..." : isBalanceSufficient ? `${totalAmount.toLocaleString()}원 결제하기` : "잔액 부족"}
          </button>

          <div className="mt-4 text-center">
            <button
              onClick={() => {
                clearCheckoutTokens(); // 결제 수동 취소 시 확실한 권한 회수
                navigate(-1);
              }}
              className="text-gray-400 hover:text-gray-600 text-sm font-medium underline"
            >
              결제 취소하고 좌석표로 돌아가기
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};
