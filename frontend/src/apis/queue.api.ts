import { apiClient } from "./apiClient";

export interface QueueEntryResponse {
  concertId: number;
  rank: number;
  status: "WAITING" | "ACTIVE";
}

export interface QueueStatusResponse {
  concertId: number;
  rank: number;
  status: "WAITING" | "ACTIVE" | "INVALID";
  accessToken: string | null;
}

/**
 * 대기열 진입 요청
 */
export const enterQueue = async (concertId: number): Promise<QueueEntryResponse> => {
  const response = await apiClient.post("/queue/entry", { concertId });
  return response.data;
};

/**
 * 대기열 순번 및 ACTIVE 전환 여부 확인 (Polling 대상)
 */
export const getQueueStatus = async (concertId: number): Promise<QueueStatusResponse> => {
  const response = await apiClient.get("/queue/status", {
    params: { concertId },
  });
  return response.data;
};
