import { useEffect, useState, useCallback } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { getSeats, type SeatResponse } from "../apis/concert.api";
import { reserveSeats } from "../apis/reservation.api";
import { useAuthStore } from "../store/useAuthStore";
import { isAxiosError } from "axios";

export const SeatSelectionPage = () => {
  const location = useLocation();
  const navigate = useNavigate();
  const { setPaymentToken } = useAuthStore();

  const state = location.state as { scheduleId?: number; concertId?: number } | undefined;
  const scheduleId = state?.scheduleId;
  const concertId = state?.concertId || 1;

  const [seats, setSeats] = useState<SeatResponse[]>([]);
  const [selectedSeatIds, setSelectedSeatIds] = useState<number[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isReserving, setIsReserving] = useState(false);

  // 동시성 경합 실패 시 즉각적인 화면 동기화를 위해 좌석 조회 로직을 분리함
  const fetchSeats = useCallback(async () => {
    if (!scheduleId) return;
    try {
      setIsLoading(true);
      const res = await getSeats(scheduleId);
      setSeats(res.seats);
    } catch (error) {
      console.error("좌석 정보 조회 실패:", error);
      alert("좌석 정보를 불러오는 데 실패했습니다.");
    } finally {
      setIsLoading(false);
    }
  }, [scheduleId]);

  useEffect(() => {
    if (!scheduleId) {
      alert("잘못된 접근입니다.");
      navigate("/");
      return;
    }
    fetchSeats();
  }, [scheduleId, fetchSeats]);

  const toggleSeatSelection = (seatId: number) => {
    setSelectedSeatIds((prev) => {
      if (prev.includes(seatId)) {
        return prev.filter((id) => id !== seatId);
      }

      // 백엔드 policy.max-seats-per-user 제약조건 동기화
      if (prev.length >= 4) {
        alert("최대 4석까지만 예매할 수 있습니다.");
        return prev;
      }
      return [...prev, seatId];
    });
  };

  const handleReservation = async () => {
    if (selectedSeatIds.length === 0) return;

    try {
      setIsReserving(true);

      // 다중 좌석 예매 시 발생하는 DB Row Lock 데드락을 방어하기 위한 필수 오름차순 정렬
      const sortedSeatIds = [...selectedSeatIds].sort((a, b) => a - b);

      const res = await reserveSeats({
        concertId,
        seatIds: sortedSeatIds,
      });

      // 점유 성공 시 LEVEL 3 결제 권한 부여
      setPaymentToken(res.accessToken);

      // 결제 페이지 라우팅 시 백엔드 검증에 필요한 concertId를 누락 없이 전달함
      navigate("/payment", {
        state: {
          groupId: res.groupId,
          expiredAt: res.expiredAt,
          totalAmount: seats.filter((s) => selectedSeatIds.includes(s.seatId)).reduce((sum, s) => sum + s.price, 0),
          concertId: concertId,
        },
      });
    } catch (error) {
      if (isAxiosError(error) && error.response) {
        const errorCode = error.response.data?.code;

        // 타인에 의한 선점(CAS 실패) 에러 발생 시 Fail-Fast 및 화면 최신화
        if (errorCode === "S004") {
          alert("이미 선택되었거나 예매 중인 좌석이 포함되어 있습니다.\n좌석표를 다시 불러옵니다.");
          setSelectedSeatIds([]);
          fetchSeats();
          return;
        }
      }
      console.error("좌석 점유 실패:", error);
      alert("좌석 점유에 실패했습니다. 다시 시도해주세요.");
    } finally {
      setIsReserving(false);
    }
  };

  if (isLoading) {
    return <div className="flex justify-center items-center min-h-[80vh] text-gray-500">좌석 배치도를 불러오는 중...</div>;
  }

  return (
    <div className="max-w-5xl mx-auto py-12 px-4 flex flex-col md:flex-row gap-8">
      {/* 왼쪽: 좌석 배치도 영역 */}
      <div className="flex-1 bg-white p-8 rounded-2xl shadow-sm border border-gray-100">
        <div className="flex justify-between items-center mb-6">
          <h1 className="text-2xl font-bold text-gray-800">좌석 선택</h1>
          <button
            onClick={fetchSeats}
            className="text-sm font-medium text-blue-600 hover:text-blue-800 transition px-3 py-1 bg-blue-50 rounded-lg"
          >
            새로고침
          </button>
        </div>

        {/* STAGE 표시 */}
        <div className="w-full bg-gray-200 text-gray-500 text-center py-4 rounded-xl mb-12 font-bold tracking-[0.5em]">STAGE</div>

        {/* 좌석 그리드 (5000석 규모를 렌더링하기 위한 촘촘한 Flex 래핑) */}
        <div className="flex flex-wrap gap-2 justify-center max-h-[500px] overflow-y-auto p-2">
          {seats.map((seat) => {
            const isSelected = selectedSeatIds.includes(seat.seatId);
            const isAvailable = seat.status === "AVAILABLE";

            return (
              <button
                key={seat.seatId}
                disabled={!isAvailable}
                onClick={() => toggleSeatSelection(seat.seatId)}
                className={`
                  w-8 h-8 rounded-t-lg text-xs font-bold transition flex items-center justify-center
                  ${isSelected ? "bg-blue-600 text-white shadow-md transform -translate-y-1" : ""}
                  ${isAvailable && !isSelected ? "bg-gray-50 border border-gray-300 text-gray-500 hover:bg-blue-50 hover:border-blue-300" : ""}
                  ${seat.status === "HOLD" ? "bg-gray-300 text-transparent cursor-not-allowed" : ""}
                  ${seat.status === "SOLD" ? "bg-gray-800 text-transparent cursor-not-allowed" : ""}
                `}
                title={`좌석 ${seat.seatNo}번 (${seat.price.toLocaleString()}원)`}
              >
                {/* 선택된 좌석이나 예매 가능한 좌석만 번호 표시, 나머지는 빈칸 */}
                {(isAvailable || isSelected) && seat.seatNo}
              </button>
            );
          })}
        </div>
      </div>

      {/* 오른쪽: 선택 정보 및 결제 진행 영역 */}
      <div className="w-full md:w-96 flex flex-col gap-6">
        {/* 범례 (Legend) */}
        <div className="bg-white p-6 rounded-2xl shadow-sm border border-gray-100 flex justify-between">
          <div className="flex flex-col items-center gap-2">
            <div className="w-6 h-6 border border-gray-300 bg-gray-50 rounded-t-md"></div>
            <span className="text-xs text-gray-600">선택가능</span>
          </div>
          <div className="flex flex-col items-center gap-2">
            <div className="w-6 h-6 bg-blue-600 rounded-t-md"></div>
            <span className="text-xs text-gray-600">선택됨</span>
          </div>
          <div className="flex flex-col items-center gap-2">
            <div className="w-6 h-6 bg-gray-300 rounded-t-md"></div>
            <span className="text-xs text-gray-600">결제진행중</span>
          </div>
          <div className="flex flex-col items-center gap-2">
            <div className="w-6 h-6 bg-gray-800 rounded-t-md"></div>
            <span className="text-xs text-gray-600">판매완료</span>
          </div>
        </div>

        {/* 선택 내역 */}
        <div className="bg-white p-6 rounded-2xl shadow-sm border border-gray-100 flex-1 flex flex-col">
          <h2 className="text-lg font-bold text-gray-800 mb-4">선택 좌석 내역</h2>
          <div className="flex-1">
            {selectedSeatIds.length === 0 ? (
              <p className="text-gray-400 text-sm">선택된 좌석이 없습니다.</p>
            ) : (
              <ul className="flex flex-col gap-3">
                {selectedSeatIds.map((id) => {
                  const seatInfo = seats.find((s) => s.seatId === id);
                  return (
                    <li key={id} className="flex justify-between text-sm border-b pb-2">
                      <span className="font-semibold text-gray-700">좌석 {seatInfo?.seatNo}번</span>
                      <span className="text-gray-600">{seatInfo?.price.toLocaleString()} 원</span>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>

          <div className="mt-6 pt-4 border-t border-gray-200 mb-6">
            <div className="flex justify-between items-end">
              <span className="text-gray-600 font-medium">총 결제 예정 금액</span>
              <span className="text-2xl font-bold text-blue-600">
                {seats
                  .filter((s) => selectedSeatIds.includes(s.seatId))
                  .reduce((sum, s) => sum + s.price, 0)
                  .toLocaleString()}
                <span className="text-lg ml-1">원</span>
              </span>
            </div>
          </div>

          <button
            onClick={handleReservation}
            disabled={selectedSeatIds.length === 0 || isReserving}
            className={`w-full py-4 rounded-xl font-bold text-lg transition ${
              selectedSeatIds.length > 0
                ? "bg-blue-600 text-white hover:bg-blue-700 shadow-md"
                : "bg-gray-200 text-gray-400 cursor-not-allowed"
            }`}
          >
            {isReserving ? "좌석 점유 중..." : "예매하기"}
          </button>
        </div>
      </div>
    </div>
  );
};
