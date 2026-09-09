import { apiClient } from './client'
import type { ApiResponse, LoginResponse, SignupResponse } from '../types/api'

export interface LoginCredentials {
  email: string
  password: string
}

export interface SignupRequest {
  email: string
  password: string
  name: string
}

// 이메일·비밀번호·이름을 Backend 회원가입 API에 전달한다.
export async function signupRequest(request: SignupRequest) {
  const { data } = await apiClient.post<ApiResponse<SignupResponse>>('/api/auth/signup', request)
  if (!data.success || !data.data) {
    throw new Error(data.error?.message || '회원가입에 실패했습니다.')
  }
  return data.data
}

// 이메일과 비밀번호를 Backend 로그인 API에 전달한다.
export async function loginRequest(credentials: LoginCredentials) {
  const { data } = await apiClient.post<ApiResponse<LoginResponse>>('/api/auth/login', credentials)
  if (!data.success || !data.data) {
    throw new Error(data.error?.message || '로그인에 실패했습니다.')
  }
  return data.data
}

// Backend가 Refresh Token을 폐기하고 HttpOnly Cookie를 만료시키도록 요청한다.
export async function logoutRequest() {
  await apiClient.post<ApiResponse<null>>('/api/auth/logout')
}
