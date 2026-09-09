import type { ApiResponse } from '../types/api'
import type { CurrentUserResponse } from '../types/user'
import { apiClient } from './client'

// 현재 인증 사용자의 Backend 공개 정보를 조회한다.
export async function fetchCurrentUser() {
  const { data } = await apiClient.get<ApiResponse<CurrentUserResponse>>('/api/users/me')
  if (!data.success || !data.data) throw new Error(data.error?.message || '사용자 정보를 불러오지 못했습니다.')
  return data.data
}

// 현재 사용자 정보를 식별하는 공통 Query Key를 반환한다.
export function currentUserQueryKey() {
  return ['current-user'] as const
}
