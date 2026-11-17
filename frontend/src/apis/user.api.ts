import { apiClient } from "./apiClient";

interface UserBalanceResponse {
  userId: number;
  balance: number;
}

/**
 * 현재 로그인한 유저의 잔액 정보 조회
 */
export const getMyBalance = async (): Promise<UserBalanceResponse> => {
  const response = await apiClient.get("/users/me/balance");
  return response.data;
};

/**
 * 로그인한 본인의 잔액 충전
 */
export const chargeBalance = async (amount: number): Promise<UserBalanceResponse> => {
  const response = await apiClient.patch("/users/me/balance", { amount });
  return response.data;
};
