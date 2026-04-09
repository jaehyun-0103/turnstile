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

/**
 * 최종 결제 승인 요청 (PAYMENT 토큰 필요)
 */
export const confirmPayment = async (request: PaymentRequest): Promise<PaymentResponse> => {
  const response = await apiClient.post("/payments", request);
  return response.data;
};
