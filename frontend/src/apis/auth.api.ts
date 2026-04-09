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
 * 사용자 로그인 및 IDENTITY 토큰 발급
 */
export const login = async (data: LoginRequest): Promise<LoginResponse> => {
  const response = await apiClient.post("/auth/login", data);
  return response.data;
};
