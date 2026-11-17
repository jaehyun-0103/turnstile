import { useEffect, useState, useMemo, type FormEvent } from "react";
import { useAuthStore } from "../store/useAuthStore";
import { getMyBalance, chargeBalance } from "../apis/user.api";
import { getMyReservations, type MyReservationResponse } from "../apis/reservation.api";
import toast from "react-hot-toast";

// 그룹화된 예매 내역의 타입을 정의
interface GroupedReservation {
  groupId: string;
  concertTitle: string;
  concertDate: string;
  status: string;
  reservedAt: string;
  totalPrice: number;
  seats: number[];
}

/**
 * 사용자의 기본 정보, 실시간 잔액 동기화 및 예약(예매) 내역을 관리하는 마이페이지 컴포넌트입니다.
 */
export const MyPage = () => {
  const { userInfo, updateBalance } = useAuthStore();

  // 상태 관리: 잔액 충전 입력값 및 예매 내역
  const [chargeAmount, setChargeAmount] = useState<number | "">("");
  const [isLoading, setIsLoading] = useState(false);
  const [reservations, setReservations] = useState<MyReservationResponse[]>([]);
  const [isReservationsLoading, setIsReservationsLoading] = useState(true);

  // 컴포넌트 마운트 시 최신 잔액 및 예약 내역 Fetching
  useEffect(() => {
    const fetchBalance = async () => {
      try {
        const res = await getMyBalance();
        updateBalance(res.balance);
      } catch (error) {
        console.error("[User] Fetch Balance | Error - 잔액 조회 실패", error);
      }
    };

    const fetchReservations = async () => {
      try {
        const res = await getMyReservations();
        setReservations(res);
      } catch (error) {
        console.warn("[Reservation] Fetch My List | Unauthorized or Error - 예매 내역 접근 권한 없음", error);
      } finally {
        setIsReservationsLoading(false);
      }
    };

    fetchBalance();
    fetchReservations();
  }, [updateBalance]);

  // 잔액 충전 폼 제출 핸들러
  const handleCharge = async (e: FormEvent) => {
    e.preventDefault();
    if (!userInfo) {
      toast.error("로그인 정보가 없습니다.");
      return;
    }
    if (!chargeAmount || chargeAmount <= 0) {
      toast.error("올바른 금액을 입력해주세요.");
      return;
    }

    try {
      setIsLoading(true);
            const res = await chargeBalance(Number(chargeAmount));

      updateBalance(res.balance); // 스토어 잔액 업데이트
      toast.success(`${chargeAmount.toLocaleString()}원이 충전되었습니다.`);
      setChargeAmount("");
    } catch (error) {
      console.error("[User] Charge Balance | Error - 충전 실패", error);
      toast.error("충전에 실패했습니다.");
    } finally {
      setIsLoading(false);
    }
  };

  // 프론트엔드 단에서 EXPIRED 필터링 및 groupId 기준 데이터 그룹화
  const groupedReservations = useMemo(() => {
    // 결제된 데이터만 표기
    const validReservations = reservations.filter((res) => res.status === "PAID");

    // groupId를 Key로 사용하여 그룹화 및 금액 합산, 좌석 번호 수집
    const groups = validReservations.reduce(
      (acc, curr) => {
        if (!acc[curr.groupId]) {
          acc[curr.groupId] = {
            groupId: curr.groupId,
            concertTitle: curr.concertTitle,
            concertDate: curr.concertDate,
            status: curr.status,
            reservedAt: curr.reservedAt,
            totalPrice: 0,
            seats: [],
          };
        }
        acc[curr.groupId].totalPrice += curr.price;
        acc[curr.groupId].seats.push(curr.seatNo);
        return acc;
      },
      {} as Record<string, GroupedReservation>,
    );

    return Object.values(groups);
  }, [reservations]);

  if (!userInfo) return null;

  return (
    <div className="max-w-4xl mx-auto py-12 px-4 flex flex-col gap-8">
      <h1 className="text-2xl font-bold text-gray-800">마이페이지</h1>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
        {/* 좌측: 내 정보 및 잔액 관리 영역 */}
        <div className="flex flex-col gap-8">
          <div className="bg-white rounded-xl shadow-sm border border-gray-100 overflow-hidden">
            <div className="p-6 border-b border-gray-100">
              <h2 className="text-lg font-semibold text-gray-700">내 정보</h2>
              <div className="mt-4 flex flex-col gap-2">
                <p className="text-gray-600">
                  <span className="font-medium inline-block w-16">이름:</span>
                  {userInfo.name}
                </p>
                <p className="text-gray-600">
                  <span className="font-medium inline-block w-16">아이디:</span>
                  {userInfo.username}
                </p>
              </div>
            </div>

            <div className="p-6 bg-blue-50 flex justify-between items-center">
              <h2 className="text-lg font-semibold text-blue-900">현재 보유 잔액</h2>
              <p className="text-2xl font-bold text-blue-600">
                {userInfo.balance.toLocaleString()} <span className="text-lg font-medium">원</span>
              </p>
            </div>
          </div>

          {/* 잔액 충전 폼 */}
          <div className="bg-white rounded-xl shadow-sm border border-gray-100 p-6">
            <h2 className="text-lg font-semibold text-gray-700 mb-4">잔액 충전 (테스트용)</h2>
            <form onSubmit={handleCharge} className="flex gap-4">
              <input
                type="number"
                value={chargeAmount}
                onChange={(e) => setChargeAmount(e.target.value === "" ? "" : Number(e.target.value))}
                placeholder="충전할 금액 입력"
                className="flex-1 px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 outline-none"
              />
              <button
                type="submit"
                disabled={isLoading}
                className={`px-6 py-2 text-white font-semibold rounded-lg transition ${
                  isLoading ? "bg-gray-400" : "bg-blue-600 hover:bg-blue-700"
                }`}
              >
                {isLoading ? "처리 중..." : "충전하기"}
              </button>
            </form>
          </div>
        </div>

        {/* 우측: 내 예매 내역 영역 */}
        <div className="bg-white rounded-xl shadow-sm border border-gray-100 p-6 flex flex-col h-full">
          <h2 className="text-lg font-semibold text-gray-700 mb-4">내 예매 내역</h2>

          <div className="flex-1 overflow-y-auto max-h-[500px] pr-2">
            {isReservationsLoading ? (
              <div className="flex justify-center items-center h-40 text-gray-400">내역을 불러오는 중...</div>
            ) : groupedReservations.length === 0 ? (
              <div className="flex flex-col justify-center items-center h-40 text-gray-400 px-4 text-center">
                <svg className="w-12 h-12 mb-3 text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth="2"
                    d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2"
                  ></path>
                </svg>
                <p>완료된 예매 내역이 없습니다.</p>
              </div>
            ) : (
              <ul className="flex flex-col gap-4">
                {groupedReservations.map((group) => (
                  <li key={group.groupId} className="p-4 border rounded-xl transition shadow-sm border-gray-100 bg-gray-50 hover:bg-white">
                    <div className="flex justify-between items-start mb-2">
                      <h3 className="font-bold text-gray-800">{group.concertTitle}</h3>
                      <span className="text-xs font-semibold px-2 py-1 rounded-md bg-green-100 text-green-700">결제 완료</span>
                    </div>
                    <p className="text-sm text-gray-600 mb-1">
                      <span className="font-medium">공연일시:</span> {new Date(group.concertDate).toLocaleString()}
                    </p>
                    <p className="text-sm text-gray-600 mb-3">
                      <span className="font-medium">좌석번호:</span>
                      <span className="font-semibold px-1">{group.seats.sort((a, b) => a - b).join(", ")}</span>번
                    </p>
                    <div className="flex justify-between items-end pt-3 border-t border-gray-200">
                      <span className="text-xs text-gray-400">결제일: {new Date(group.reservedAt).toLocaleDateString()}</span>
                      <span className="font-bold text-blue-600">총 {group.totalPrice.toLocaleString()} 원</span>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};