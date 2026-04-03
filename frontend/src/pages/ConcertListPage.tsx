import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { getConcerts, getConcertSchedules, type ConcertResponse, type ConcertScheduleResponse } from "../apis/concert.api";

export const ConcertListPage = () => {
  const navigate = useNavigate();

  const [concerts, setConcerts] = useState<ConcertResponse[]>([]);
  const [selectedConcert, setSelectedConcert] = useState<ConcertResponse | null>(null);

  const [schedules, setSchedules] = useState<ConcertScheduleResponse[]>([]);
  const [selectedScheduleId, setSelectedScheduleId] = useState<number | null>(null);

  const [isLoading, setIsLoading] = useState(true);
  const [isScheduleLoading, setIsScheduleLoading] = useState(false);

  // 초기 렌더링 시 콘서트 목록을 읽어옴
  useEffect(() => {
    const fetchConcerts = async () => {
      try {
        const data = await getConcerts();
        setConcerts(data);
      } catch (error) {
        console.error("콘서트 목록 조회 실패:", error);
      } finally {
        setIsLoading(false);
      }
    };
    fetchConcerts();
  }, []);

  // 콘서트 클릭 시 동작 (오픈 시간 검증 및 회차를 읽어옴)
  const handleConcertClick = async (concert: ConcertResponse) => {
    // 예매 오픈 시간 검증 (TICKETING_NOT_OPEN 에러 사전 방어)
    const now = new Date();
    const openTime = new Date(concert.ticketingStartAt);

    if (now < openTime) {
      alert(`아직 예매가 오픈되지 않았습니다.\n오픈 일시: ${openTime.toLocaleString()}`);
      return;
    }

    setSelectedConcert(concert);
    setSelectedScheduleId(null);
    setIsScheduleLoading(true);

    try {
      const data = await getConcertSchedules(concert.id);
      setSchedules(data);
    } catch (error) {
      console.error("회차 목록 조회 실패:", error);
    } finally {
      setIsScheduleLoading(false);
    }
  };

  // 예매하기 (대기열 진입)
  const handleTicketing = () => {
    if (!selectedConcert || !selectedScheduleId) return;

    navigate("/queue", {
      state: {
        concertId: selectedConcert.id,
        scheduleId: selectedScheduleId,
      },
    });
  };

  if (isLoading) {
    return <div className="flex justify-center items-center h-screen text-gray-500">콘서트 정보를 불러오는 중...</div>;
  }

  return (
    <div className="max-w-5xl mx-auto py-12 px-4 flex flex-col md:flex-row gap-8">
      {/* 왼쪽: 콘서트 목록 영역 */}
      <div className="flex-1">
        <h1 className="text-2xl font-bold text-gray-800 mb-6">진행 중인 공연</h1>
        <div className="flex flex-col gap-4">
          {concerts.map((concert) => (
            <div
              key={concert.id}
              onClick={() => handleConcertClick(concert)}
              className={`p-6 rounded-xl border-2 cursor-pointer transition ${
                selectedConcert?.id === concert.id ? "border-blue-500 bg-blue-50" : "border-gray-200 bg-white hover:border-blue-300"
              }`}
            >
              <h2 className="text-xl font-bold text-gray-900">{concert.title}</h2>
              <p className="text-gray-500 mt-2 text-sm">{concert.description}</p>
            </div>
          ))}
          {concerts.length === 0 && <p className="text-gray-500 text-center py-8">현재 예매 가능한 공연이 없습니다.</p>}
        </div>
      </div>

      {/* 오른쪽: 회차(Schedule) 선택 영역 */}
      <div className="w-full md:w-96">
        <h2 className="text-xl font-bold text-gray-800 mb-6">회차 선택</h2>
        <div className="bg-white rounded-xl border border-gray-200 p-6 min-h-[300px] flex flex-col">
          {!selectedConcert ? (
            <div className="flex-1 flex items-center justify-center text-gray-400 text-sm">왼쪽에서 공연을 선택해주세요.</div>
          ) : isScheduleLoading ? (
            <div className="flex-1 flex items-center justify-center text-gray-400 text-sm">회차 정보를 불러오는 중...</div>
          ) : (
            <>
              <div className="flex flex-col gap-3 flex-1 mb-6">
                {schedules.map((schedule) => (
                  <button
                    key={schedule.id}
                    onClick={() => setSelectedScheduleId(schedule.id)}
                    className={`p-4 rounded-lg text-left border transition ${
                      selectedScheduleId === schedule.id
                        ? "border-blue-500 bg-blue-600 text-white"
                        : "border-gray-300 hover:bg-gray-50 text-gray-700"
                    }`}
                  >
                    {/* schedule.date 로 정상 렌더링되도록 반영됨 */}
                    <span className="font-semibold block">{new Date(schedule.date).toLocaleString()}</span>
                    <span className="text-sm opacity-80 mt-1 block">총 {schedule.totalSeats}석</span>
                  </button>
                ))}
              </div>

              <button
                onClick={handleTicketing}
                disabled={!selectedScheduleId}
                className={`w-full py-3 rounded-lg font-bold transition ${
                  selectedScheduleId ? "bg-blue-600 text-white hover:bg-blue-700" : "bg-gray-200 text-gray-400 cursor-not-allowed"
                }`}
              >
                예매하기 (대기열 진입)
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
};
