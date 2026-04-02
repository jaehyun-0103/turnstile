import { apiClient } from "./apiClient";

interface LoginRequest {
  username: string;
}

interface LoginResponse {
  userId: number;
  username: string;
  name: string;
  balance: number;
  accessToken: string;
}

/**
 * 사용자 로그인을 수행하고 IDENTITY 토큰 및 유저 정보를 반환합니다.
 */
export const login = async (data: LoginRequest): Promise<LoginResponse> => {
  const response = await apiClient.post("/api/v1/auth/login", data);
  return response.data;
};
