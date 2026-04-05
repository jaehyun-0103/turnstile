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

export interface MyReservationResponse {
  reservationId: number;
  groupId: string;
  concertTitle: string;
  concertDate: string;
  seatNo: number;
  status: string;
  price: number;
  reservedAt: string;
}

// 다중 좌석 점유 시 발생하는 DB 레벨의 데드락을 방지하기 위해 호출부에서 seatIds를 오름차순 정렬하여 전달해야 함
export const reserveSeats = async (request: ReservationRequest): Promise<ReservationResponse> => {
  const response = await apiClient.post("/api/v1/reservations", request);
  return response.data;
};

// IDENTITY 토큰만으로 조회가 가능한 엔드포인트
export const getMyReservations = async (): Promise<MyReservationResponse[]> => {
  const response = await apiClient.get("/api/v1/reservations/my");
  return response.data;
};