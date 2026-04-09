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

/**
 * 좌석 임시 점유 요청
 * @important 데드락 방지를 위해 호출부에서 seatIds 오름차순 정렬 필수
 */
export const reserveSeats = async (request: ReservationRequest): Promise<ReservationResponse> => {
  const response = await apiClient.post("/reservations", request);
  return response.data;
};

/**
 * 내 예매 내역 목록 조회
 */
export const getMyReservations = async (): Promise<MyReservationResponse[]> => {
  const response = await apiClient.get("/reservations/my");
  return response.data;
};