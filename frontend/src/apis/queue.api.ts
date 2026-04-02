import { apiClient } from "./apiClient";

export interface QueueEntryResponse {
  concertId: number;
  rank: number;
  status: "WAITING" | "ACTIVE";
}

export interface QueueStatusResponse {
  concertId: number;
  rank: number;
  status: "WAITING" | "ACTIVE";
  accessToken: string | null; // ACTIVE 전환 시에만 값이 존재함
}

// 회차 선택 후 '예매하기' 클릭 시 최초 1회 호출되어 대기열(ZSET)에 유저를 등록함
export const enterQueue = async (concertId: number): Promise<QueueEntryResponse> => {
  const response = await apiClient.post("/api/v1/queue/entry", { concertId });
  return response.data;
};

// WAITING 상태인 유저가 3초 주기로 자신의 순번을 확인하고, ACTIVE로 상태가 전이되었는지(토큰이 발급되었는지) 검사함
export const getQueueStatus = async (concertId: number): Promise<QueueStatusResponse> => {
  const response = await apiClient.get("/api/v1/queue/status", {
    params: { concertId },
  });
  return response.data;
};
