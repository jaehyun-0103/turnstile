import { useEffect, useState, useRef } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { enterQueue, getQueueStatus } from "../apis/queue.api";
import { useAuthStore } from "../store/useAuthStore";

// 대기열 진입 및 상태 폴링을 수행하고 인가 토큰을 획득하는 페이지 컴포넌트.
export const QueueWaitingPage = () => {
  const location = useLocation();
  const navigate = useNavigate();
  const { setQueueToken } = useAuthStore();

  const state = location.state as { concertId?: number; scheduleId?: number } | null;
  const concertId = state?.concertId;
  const scheduleId = state?.scheduleId;

  const [rank, setRank] = useState<number>(0);
  const [status, setStatus] = useState<"ENTRY" | "WAITING" | "ACTIVE">("ENTRY");

  // 브라우저 환경의 setInterval 반환값을 저장하여 컴포넌트 언마운트 시 메모리 누수를 방지함
  const intervalRef = useRef<number | null>(null);

  useEffect(() => {
    if (!concertId || !scheduleId) {
      alert("잘못된 접근입니다. 콘서트를 다시 선택해주세요.");
      navigate("/");
      return;
    }

    // 비동기 통신 중 컴포넌트가 언마운트되었을 때 상태 업데이트를 시도하여 발생하는 메모리 누수 경고를 방어함
    let isMounted = true;

    const checkStatus = async () => {
      try {
        const statusRes = await getQueueStatus(concertId);
        if (!isMounted) return;

        setRank(statusRes.rank);

        if (statusRes.status === "ACTIVE" && statusRes.accessToken) {
          if (intervalRef.current) clearInterval(intervalRef.current);
          setStatus("ACTIVE");

          setQueueToken(statusRes.accessToken);

          // 토큰 발급 직후 즉각적인 라우팅 시 발생할 수 있는 시각적 끊김을 완화하기 위해 지연 이동 처리함
          setTimeout(() => {
            navigate("/seats", { state: { scheduleId } });
          }, 1500);
        }
      } catch (error) {
        console.error("대기열 상태 확인 실패:", error);
      }
    };

    const startQueue = async () => {
      try {
        const entryRes = await enterQueue(concertId);
        if (!isMounted) return;

        setRank(entryRes.rank);
        setStatus(entryRes.status as "WAITING" | "ACTIVE");

        if (entryRes.status === "ACTIVE") {
          checkStatus();
          return;
        }

        intervalRef.current = window.setInterval(() => {
          checkStatus();
        }, 3000);
      } catch (error) {
        console.error("대기열 진입 실패:", error);
        alert("대기열 진입 중 오류가 발생했습니다. 다시 시도해주세요.");
        navigate("/");
      }
    };

    startQueue();

    // 컴포넌트 언마운트 시 활성화된 폴링 타이머를 해제하여 불필요한 네트워크 리소스 점유 및 좀비 폴링을 차단함
    return () => {
      isMounted = false;
      if (intervalRef.current) clearInterval(intervalRef.current);
    };
  }, [concertId, scheduleId, navigate, setQueueToken]);

  return (
    <div className="flex flex-col items-center justify-center min-h-[80vh] px-4">
      <div className="bg-white p-10 rounded-2xl shadow-lg border border-gray-100 max-w-md w-full text-center flex flex-col items-center">
        <div className="w-16 h-16 mb-6">
          {status === "ACTIVE" ? (
            <svg className="w-full h-full text-green-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M5 13l4 4L19 7"></path>
            </svg>
          ) : (
            <div className="w-full h-full border-4 border-gray-200 border-t-blue-600 rounded-full animate-spin"></div>
          )}
        </div>

        <h1 className="text-2xl font-bold text-gray-800 mb-2">
          {status === "ENTRY" && "대기열에 진입하고 있습니다..."}
          {status === "WAITING" && "현재 예매 대기 중입니다."}
          {status === "ACTIVE" && "입장 준비 완료!"}
        </h1>

        {status === "WAITING" ? (
          <>
            <p className="text-gray-500 mb-8">
              잠시만 기다려주시면 좌석 선택 창으로 이동합니다.
              <br />
              새로고침 시 대기 순서가 밀릴 수 있습니다.
            </p>
            <div className="bg-blue-50 w-full py-6 rounded-xl border border-blue-100">
              <p className="text-sm font-semibold text-blue-800 mb-1">내 앞 대기자 수</p>
              <p className="text-5xl font-extrabold text-blue-600 tracking-tight">
                {rank.toLocaleString()}
                <span className="text-2xl font-bold ml-1 text-blue-400">명</span>
              </p>
            </div>
          </>
        ) : status === "ACTIVE" ? (
          <p className="text-green-600 font-medium mt-4">좌석 선택 페이지로 이동합니다...</p>
        ) : null}

        {status === "WAITING" && (
          <button onClick={() => navigate("/")} className="mt-8 text-sm text-gray-400 underline hover:text-gray-600 transition">
            대기 취소하고 나가기
          </button>
        )}
      </div>
    </div>
  );
};
