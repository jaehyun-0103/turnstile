import { useEffect, useState, useCallback } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { getMyBalance } from "../apis/user.api";
import { useAuthStore } from "../store/useAuthStore";

export const PaymentPage = () => {
  const location = useLocation();
  const navigate = useNavigate();
  const { setPaymentToken, userInfo, updateBalance } = useAuthStore();

  // null 대신 undefined 사용, 백엔드 검증을 위한 concertId 추가 수신
  const state = location.state as { groupId?: string; expiredAt?: string; totalAmount?: number; concertId?: number } | undefined;
  const groupId = state?.groupId;
  const expiredAt = state?.expiredAt;
  const totalAmount = state?.totalAmount || 0;
  const concertId = state?.concertId;

  const [timeLeft, setTimeLeft] = useState<string>("05:30");
  const [isExpired, setIsExpired] = useState<boolean>(false);
  const [isLoading, setIsLoading] = useState<boolean>(true);

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

  useEffect(() => {
    // 필수 데이터 누락 시 튕겨냄
    if (!groupId || !expiredAt || !concertId) {
      alert("비정상적인 접근입니다.");
      navigate("/");
      return;
    }

    fetchBalance();

    const targetTime = new Date(expiredAt).getTime();

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
        clearInterval(timerId);
        alert("결제 시간이 만료되어 예매가 취소되었습니다.");
        setPaymentToken(""); // null 대신 빈 문자열로 토큰 폐기 처리
        navigate("/");
      }
    }, 1000);

    return () => clearInterval(timerId);
  }, [groupId, expiredAt, concertId, navigate, setPaymentToken, fetchBalance]);

  const handlePayment = () => {
    alert("결제 API 연동 전입니다. (3번 커밋에서 구현 예정)");
  };

  const currentBalance = userInfo?.balance ?? 0;
  const isBalanceSufficient = currentBalance >= totalAmount;

  if (isLoading) {
    return <div className="flex justify-center items-center min-h-[80vh]">결제 정보를 불러오는 중...</div>;
  }

  return (
    <div className="max-w-2xl mx-auto py-12 px-4">
      <div className="bg-white rounded-3xl shadow-sm border border-gray-100 overflow-hidden">
        <div className="bg-blue-600 p-8 text-center text-white">
          <h1 className="text-2xl font-bold mb-2">결제 대기 중</h1>
          <p className="text-blue-100 mb-6">아래 시간 내에 결제를 완료하지 않으면 좌석이 취소됩니다.</p>
          <div className="inline-block bg-white/20 px-6 py-3 rounded-2xl backdrop-blur-sm border border-white/30">
            <span className="text-4xl font-mono font-bold tracking-wider">{timeLeft}</span>
          </div>
        </div>

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
            disabled={isExpired || !isBalanceSufficient}
            className={`w-full py-4 rounded-xl font-bold text-lg transition ${
              isExpired || !isBalanceSufficient
                ? "bg-gray-200 text-gray-400 cursor-not-allowed"
                : "bg-blue-600 text-white hover:bg-blue-700 shadow-md"
            }`}
          >
            {isBalanceSufficient ? `${totalAmount.toLocaleString()}원 결제하기` : "잔액 부족"}
          </button>

          <div className="mt-4 text-center">
            <button
              onClick={() => {
                setPaymentToken(""); // null 제거
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
