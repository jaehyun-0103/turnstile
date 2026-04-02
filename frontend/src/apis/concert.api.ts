import { apiClient } from "./apiClient";

export interface ConcertResponse {
  id: number;
  title: string;
  description: string;
  ticketingStartAt: string;
}

export interface ConcertScheduleResponse {
  id: number;
  concertId: number;
  concertDate: string;
  totalSeats: number;
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
