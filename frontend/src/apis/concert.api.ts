import { apiClient } from "./apiClient";

export interface ConcertResponse {
  id: number;
  title: string;
  description: string;
  ticketingStartAt: string;
}

export interface ConcertScheduleResponse {
  id: number;
  date: string;
  totalSeats: number;
}

export interface SeatResponse {
  seatId: number;
  seatNo: number;
  status: "AVAILABLE" | "HOLD" | "SOLD";
  price: number;
}

export interface SeatListResponse {
  scheduleId: number;
  seats: SeatResponse[];
}

/**
 * 전체 콘서트 목록 조회 (Redis 캐싱 활용 대상)
 */
export const getConcerts = async (): Promise<ConcertResponse[]> => {
  const response = await apiClient.get("/concerts");
  return response.data;
};

/**
 * 특정 콘서트의 예매 회차 목록 조회
 */
export const getConcertSchedules = async (concertId: number): Promise<ConcertScheduleResponse[]> => {
  const response = await apiClient.get(`/concerts/${concertId}/schedules`);
  return response.data;
};

/**
 * 회차별 좌석 상태 조회 (QUEUE 토큰 필요)
 */
export const getSeats = async (scheduleId: number): Promise<SeatListResponse> => {
  const response = await apiClient.get(`/schedules/${scheduleId}/seats`);
  return response.data;
};