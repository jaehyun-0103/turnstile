import { useEffect, useState, useCallback, useRef } from "react";
import { useLocation, useNavigate, useBlocker } from "react-router-dom";
import { getMyBalance } from "../apis/user.api";
import { confirmPayment } from "../apis/payment.api";
import { isAxiosError } from "axios";
import { useAuthStore } from "../store/useAuthStore";
import toast from "react-hot-toast";

/**
 * 임시 점유된 예약 건의 최종 결제를 처리하는 페이지 컴포넌트입니다.
 * 백엔드의 좌석 점유 TTL(5분 30초)과 동기화된 타이머를 운용하며, 초과 시 예약을 파기합니다.
 */
export const PaymentPage = () => {
  const location = useLocation();
  const navigate = useNavigate();
  const { userInfo, updateBalance, clearCheckoutTokens, paymentToken } = useAuthStore();

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

  const [showConfirmModal, setShowConfirmModal] = useState(false);
  const [showExitModal, setShowExitModal] = useState(false);

  const isProcessDoneRef = useRef(false);
  const cleanupTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // 의도된 이탈이 아니면서, 토큰이 있고, 경로가 바뀔 때 라우팅을 차단
  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) =>
      !isProcessDoneRef.current && paymentToken !== null && currentLocation.pathname !== nextLocation.pathname,
  );

  // blocker가 라우팅을 차단하면 커스텀 이탈 모달 노출
  useEffect(() => {
    if (blocker.state === "blocked") {
      setShowExitModal(true);
    }
  }, [blocker.state]);

  useEffect(() => {
    // 좀비 방어: 의도된 이탈이 아닌데 토큰이 없는 경우 (직접 URL 접속 등)
    if (!isProcessDoneRef.current && !paymentToken) {
      toast.error("유효하지 않은 접근이거나 결제 세션이 만료되었습니다.", { id: "zombie-guard" });
      navigate("/", { replace: true });
      return;
    }

    // 무한 로딩(새로고침) 방어: 토큰은 있지만 state가 날아간 경우
    if (!isProcessDoneRef.current && (!groupId || !expiredAt || !concertId)) {
      toast.error("결제 정보가 유실되었습니다. 다시 시도해주세요.", { id: "refresh-guard" });
      isProcessDoneRef.current = true;
      clearCheckoutTokens();
      navigate("/", { replace: true });
      return;
    }

    // Strict Mode 방어용 타이머 초기화
    if (cleanupTimerRef.current) {
      clearTimeout(cleanupTimerRef.current);
      cleanupTimerRef.current = null;
    }

    // 브라우저 탭 닫기/새로고침 방어
    const handleBeforeUnload = (e: BeforeUnloadEvent) => {
      if (!isProcessDoneRef.current) {
        e.preventDefault();
        e.returnValue = "";
      }
    };
    window.addEventListener("beforeunload", handleBeforeUnload);

    // 언보안 권한 회수
    return () => {
      window.removeEventListener("beforeunload", handleBeforeUnload);

      cleanupTimerRef.current = setTimeout(() => {
        // 의도된 이탈(결제 완료 등)이 아닐 때만 토큰을 회수
        if (!isProcessDoneRef.current) {
          clearCheckoutTokens();
        }
      }, 100);
    };
  }, [paymentToken, groupId, expiredAt, concertId, navigate, clearCheckoutTokens]);

  const fetchBalance = useCallback(async () => {
    try {
      const res = await getMyBalance();
      updateBalance(res.balance);
    } catch (error) {
      console.error("잔액 조회 실패:", error);
      toast.error("잔액 정보를 불러오지 못했습니다.");
    } finally {
      setIsLoading(false);
    }
  }, [updateBalance]);

  // 백엔드 점유 만료 시간(expiredAt) 기반 타이머 작동
  useEffect(() => {
    if (!expiredAt || isProcessDoneRef.current) return;

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
      const m = Math.floor((diff / 1000 / 60) % 60);
      const s = Math.floor((diff / 1000) % 60);
      setTimeLeft(`${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`);
      return false;
    };

    if (updateTimer()) return;

    const timerId = setInterval(() => {
      if (updateTimer()) {
        clearInterval(timerId);
        toast.error("결제 시간이 만료되어 예매가 취소되었습니다.");

        isProcessDoneRef.current = true; // 타이머 만료도 의도된 이탈로 간주
        clearCheckoutTokens();
        navigate("/", { replace: true });
      }
    }, 1000);

    return () => clearInterval(timerId);
  }, [expiredAt, fetchBalance, navigate, clearCheckoutTokens]);

  // 최종 결제 확정 핸들러
  const handlePayment = async () => {
    if (!groupId || !concertId) return;

    try {
      setShowConfirmModal(false);
      setIsPaying(true);
      const res = await confirmPayment({ concertId, groupId, amount: totalAmount });

      isProcessDoneRef.current = true;
      updateBalance(res.balance);
      clearCheckoutTokens();

      toast.success("결제가 성공적으로 완료되었습니다!");

      setTimeout(() => {
        navigate("/mypage", { replace: true });
      }, 100);
    } catch (error) {
      isProcessDoneRef.current = true;
      clearCheckoutTokens();

      const response = isAxiosError(error) ? error.response : undefined;
      const resultUnknown = !response || response.status >= 500 || response.status === 408 || response.data?.code === "P002";

      if (resultUnknown) {
        toast.error("결제 결과를 확인하지 못했습니다. 연결 상태를 확인한 뒤 마이페이지의 예매 내역을 확인해주세요.", { duration: 8000 });

        navigate("/mypage", { replace: true });
      } else {
        toast.error(response.data?.message || "결제 요청이 거부되었습니다.", { duration: 5000 });

        navigate("/", { replace: true });
      }
    } finally {
      setIsPaying(false);
    }
  };

  // 커스텀 이탈 모달 액션
  const handleConfirmExit = () => {
    isProcessDoneRef.current = true;
    clearCheckoutTokens();
    if (blocker.state === "blocked") blocker.proceed();
  };

  const handleCancelExit = () => {
    if (blocker.state === "blocked") blocker.reset();
    setShowExitModal(false);
  };

  const currentBalance = userInfo?.balance ?? 0;
  const isBalanceSufficient = currentBalance >= totalAmount;

  if (isLoading || (!isProcessDoneRef.current && !paymentToken)) {
    return <div className="flex justify-center items-center min-h-[80vh]">보안 세션 확인 중...</div>;
  }

  return (
    <div className="max-w-2xl mx-auto py-12 px-4 relative">
      <div className="bg-white rounded-3xl shadow-sm border border-gray-100 overflow-hidden">
        <div className="bg-gray-50 p-6 border-b border-gray-100">
          <h1 className="text-xl font-bold text-gray-800">결제 진행</h1>
        </div>

        <div className="p-8">
          <div className="bg-red-50 border border-red-200 rounded-xl p-5 mb-8">
            <span className="text-red-800 font-bold flex items-center gap-2 mb-2">
              <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"></path>
              </svg>
              결제 만료 임박
            </span>
            <p className="text-sm text-red-600 leading-relaxed">
              시간 내 미결제 시 좌석이 취소되며, <br />
              이탈 시 <span className="font-bold underline">남은 시간({timeLeft})</span> 동안 재예매가 제한됩니다.
            </p>
          </div>

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
          </div>

          <button
            onClick={() => setShowConfirmModal(true)}
            disabled={isExpired || !isBalanceSufficient || isPaying}
            className="w-full py-4 bg-blue-600 text-white rounded-xl font-bold text-lg disabled:bg-gray-200 shadow-md transition hover:bg-blue-700"
          >
            {isPaying ? "처리 중..." : `${totalAmount.toLocaleString()}원 결제하기`}
          </button>

          <div className="mt-4 text-center">
            <button onClick={() => navigate(-1)} className="text-gray-400 text-sm underline hover:text-gray-600">
              결제 취소하고 나가기
            </button>
          </div>
        </div>
      </div>

      {/* 결제 확인 모달 */}
      {showConfirmModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm">
          <div className="bg-white rounded-2xl p-8 max-w-sm w-full shadow-xl">
            <h3 className="text-xl font-bold text-gray-900 mb-2">결제 확인</h3>
            <p className="text-gray-600 mb-8">
              총 <span className="font-bold text-blue-600">{totalAmount.toLocaleString()}원</span>을 결제하시겠습니까?
            </p>
            <div className="flex gap-3">
              <button onClick={() => setShowConfirmModal(false)} className="flex-1 py-3 bg-gray-100 font-bold rounded-xl">
                취소
              </button>
              <button onClick={handlePayment} className="flex-1 py-3 bg-blue-600 text-white font-bold rounded-xl">
                결제
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 이탈 방지 모달 */}
      {showExitModal && (
        <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/50 backdrop-blur-sm">
          <div className="bg-white rounded-2xl p-8 max-w-sm w-full shadow-2xl">
            <div className="w-12 h-12 bg-red-100 rounded-full flex items-center justify-center mb-4">
              <svg className="w-6 h-6 text-red-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth="2"
                  d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"
                />
              </svg>
            </div>
            <h3 className="text-xl font-bold text-gray-900 mb-2">정말 나가시겠습니까?</h3>
            <p className="text-gray-600 mb-8 text-sm leading-relaxed">
              지금 나가시면 좌석 예약이 취소됩니다. <br />
              <span className="font-bold text-red-600 underline">남은 시간({timeLeft})</span> 동안 재예매가 제한됩니다.
            </p>
            <div className="flex flex-col gap-2">
              <button onClick={handleConfirmExit} className="w-full py-3 bg-red-500 text-white font-bold rounded-xl hover:bg-red-600">
                나가기
              </button>
              <button onClick={handleCancelExit} className="w-full py-3 bg-gray-100 text-gray-700 font-bold rounded-xl hover:bg-gray-200">
                계속하기
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
