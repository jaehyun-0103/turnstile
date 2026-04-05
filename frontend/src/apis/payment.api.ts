import { apiClient } from "./apiClient";

export interface PaymentRequest {
  groupId: string;
  amount: number;
  concertId: number;
}

export interface PaymentResponse {
  paymentId: number;
  status: string;
  paidAt: string;
  balance: number;
}

// 3-Tier 인가 정책에 따라 apiClient 인터셉터에서 PAYMENT 토큰이 자동으로 주입되어 검증됨
export const confirmPayment = async (request: PaymentRequest): Promise<PaymentResponse> => {
  const response = await apiClient.post("/api/v1/payments", request);
  return response.data;
};
