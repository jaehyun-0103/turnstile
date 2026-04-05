import { useEffect, useState, type FormEvent } from "react";
import { useAuthStore } from "../store/useAuthStore";
import { getMyBalance, chargeBalance } from "../apis/user.api";
import { getMyReservations, type MyReservationResponse } from "../apis/reservation.api";

export const MyPage = () => {
  const { userInfo, updateBalance } = useAuthStore();
  const [chargeAmount, setChargeAmount] = useState<number | "">("");
  const [isLoading, setIsLoading] = useState(false);
  const [reservations, setReservations] = useState<MyReservationResponse[]>([]);
  const [isReservationsLoading, setIsReservationsLoading] = useState(true);

  // 컴포넌트 마운트 시 최신 잔액 페칭
  useEffect(() => {
    const fetchBalance = async () => {
      try {
        const res = await getMyBalance();
        updateBalance(res.balance);
      } catch (error) {
        console.error("잔액 조회 실패:", error);
      }
    };

    const fetchReservations = async () => {
      try {
        const res = await getMyReservations();
        setReservations(res);
      } catch (error) {
        console.error("예약 내역 조회 실패:", error);
      } finally {
        setIsReservationsLoading(false);
      }
    };

    fetchBalance();
    fetchReservations();
  }, [updateBalance]);

  const handleCharge = async (e: FormEvent) => {
    e.preventDefault();
    if (!userInfo) return alert("로그인 정보가 없습니다.");
    if (!chargeAmount || chargeAmount <= 0) return alert("올바른 금액을 입력해주세요.");

    try {
      setIsLoading(true);
      // 충전 API 호출
      const res = await chargeBalance(userInfo.userId, Number(chargeAmount));

      // 스토어 업데이트 및 알림
      updateBalance(res.balance);
      alert(`${chargeAmount.toLocaleString()}원이 충전되었습니다.`);
      setChargeAmount("");
    } catch (error) {
      console.error("충전 실패:", error);
      alert("충전에 실패했습니다.");
    } finally {
      setIsLoading(false);
    }
  };

  if (!userInfo) return null;

  return (
    <div className="max-w-4xl mx-auto py-12 px-4 flex flex-col gap-8">
      <h1 className="text-2xl font-bold text-gray-800">마이페이지</h1>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
        {/* 왼쪽: 내 정보 및 잔액 */}
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

          <div className="bg-white rounded-xl shadow-sm border border-gray-100 p-6">
            <h2 className="text-lg font-semibold text-gray-700 mb-4">잔액 충전 (테스트용)</h2>
            <form onSubmit={handleCharge} className="flex gap-4">
              <input
                type="number"
                value={chargeAmount}
                onChange={(e) => setChargeAmount(Number(e.target.value))}
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

        {/* 오른쪽: 내 예매 내역 */}
        <div className="bg-white rounded-xl shadow-sm border border-gray-100 p-6 flex flex-col h-full">
          <h2 className="text-lg font-semibold text-gray-700 mb-4">내 예매 내역</h2>

          <div className="flex-1 overflow-y-auto max-h-[500px] pr-2">
            {isReservationsLoading ? (
              <div className="flex justify-center items-center h-40 text-gray-400">내역을 불러오는 중...</div>
            ) : reservations.length === 0 ? (
              <div className="flex flex-col justify-center items-center h-40 text-gray-400">
                <svg className="w-12 h-12 mb-3 text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth="2"
                    d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2"
                  ></path>
                </svg>
                <p>예매한 내역이 없습니다.</p>
              </div>
            ) : (
              <ul className="flex flex-col gap-4">
                {reservations.map((res) => (
                  <li
                    key={res.reservationId}
                    className="p-4 border border-gray-100 rounded-xl bg-gray-50 hover:bg-white transition shadow-sm"
                  >
                    <div className="flex justify-between items-start mb-2">
                      <h3 className="font-bold text-gray-800">{res.concertTitle}</h3>
                      <span className="text-xs font-semibold px-2 py-1 bg-green-100 text-green-700 rounded-md">
                        {res.status === "PAID" ? "결제완료" : res.status}
                      </span>
                    </div>
                    <p className="text-sm text-gray-600 mb-1">
                      <span className="font-medium">공연일시:</span> {new Date(res.concertDate).toLocaleString()}
                    </p>
                    <p className="text-sm text-gray-600 mb-3">
                      <span className="font-medium">좌석번호:</span> {res.seatNo}번
                    </p>
                    <div className="flex justify-between items-end pt-3 border-t border-gray-200">
                      <span className="text-xs text-gray-400">결제일: {new Date(res.reservedAt).toLocaleDateString()}</span>
                      <span className="font-bold text-blue-600">{res.price.toLocaleString()} 원</span>
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