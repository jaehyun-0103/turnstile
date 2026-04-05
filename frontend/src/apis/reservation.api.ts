import { apiClient } from "./apiClient";

export interface ReservationRequest {
  concertId: number;
  seatIds: number[];
}

export interface ReservationResponse {
  groupId: string;
  status: string;
  expiredAt: string;
  accessToken: string;
}

// 다중 좌석 점유 시 발생하는 DB 레벨의 데드락을 방지하기 위해 호출부에서 seatIds를 오름차순 정렬하여 전달해야 함
export const reserveSeats = async (request: ReservationRequest): Promise<ReservationResponse> => {
  const response = await apiClient.post("/api/v1/reservations", request);
  return response.data;
};
