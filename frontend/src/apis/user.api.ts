import { apiClient } from "./apiClient";

interface UserBalanceResponse {
  userId: number;
  balance: number;
}

/**
 * 로그인한 사용자의 현재 잔액을 조회합니다. (IDENTITY 토큰 필요)
 */
export const getMyBalance = async (): Promise<UserBalanceResponse> => {
  const response = await apiClient.get("/api/v1/users/me/balance");
  return response.data;
};

/**
 * 특정 사용자의 계좌에 금액을 충전합니다.
 */
export const chargeBalance = async (userId: number, amount: number): Promise<UserBalanceResponse> => {
  const response = await apiClient.patch(`/api/v1/users/${userId}/balance`, { amount });
  return response.data;
};
