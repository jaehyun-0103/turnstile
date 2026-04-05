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

// 메인 화면 로딩 시 다수의 유저가 동시에 호출하므로 백엔드의 글로벌 캐싱(Redis)을 타게 되는 엔드포인트
export const getConcerts = async (): Promise<ConcertResponse[]> => {
  const response = await apiClient.get("/api/v1/concerts");
  return response.data;
};

// 특정 콘서트 선택 시 하위 회차(Schedule) 데이터를 읽어옴
export const getConcertSchedules = async (concertId: number): Promise<ConcertScheduleResponse[]> => {
  const response = await apiClient.get(`/api/v1/concerts/${concertId}/schedules`);
  return response.data;
};

// QUEUE 토큰이 인터셉터에 의해 자동으로 주입되며 해당 회차의 좌석 상태 배열을 반환함
export const getSeats = async (scheduleId: number): Promise<SeatListResponse> => {
  const response = await apiClient.get(`/api/v1/schedules/${scheduleId}/seats`);
  return response.data;
};