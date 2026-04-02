import React, { useEffect, useState } from "react";
import { useAuthStore } from "../store/useAuthStore";
import { getMyBalance, chargeBalance } from "../apis/user.api";

export const MyPage = () => {
  const { userInfo, updateBalance } = useAuthStore();
  const [chargeAmount, setChargeAmount] = useState<number | "">("");
  const [isLoading, setIsLoading] = useState(false);

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
    fetchBalance();
  }, [updateBalance]);

  const handleCharge = async (e: React.FormEvent) => {
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
    <div className="max-w-2xl mx-auto py-12 px-4">
      <h1 className="text-2xl font-bold text-gray-800 mb-8">마이페이지</h1>

      <div className="bg-white rounded-xl shadow-sm border border-gray-100 overflow-hidden mb-8">
        <div className="p-6 border-b border-gray-100">
          <h2 className="text-lg font-semibold text-gray-700">내 정보</h2>
          <div className="mt-4 flex flex-col gap-2">
            <p className="text-gray-600">
              <span className="font-medium">이름:</span> {userInfo.name}
            </p>
            <p className="text-gray-600">
              <span className="font-medium">아이디:</span> {userInfo.username}
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
  );
};
