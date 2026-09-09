import type { AccountResponse, AccountStatusResponse } from '../types/account'
import type { ApiResponse } from '../types/api'
import { apiClient } from './client'

// JWT 소유자의 계좌 목록을 Backend 공통 응답에서 꺼내 반환한다.
export async function fetchAccounts() {
  const { data } = await apiClient.get<ApiResponse<AccountResponse[]>>('/api/accounts')
  if (!data.success || !data.data) throw new Error(data.error?.message || '계좌 목록을 불러오지 못했습니다.')
  return data.data
}

// 사용자 입력값 없이 Backend에 새 계좌 생성을 요청한다.
export async function createAccount() {
  const { data } = await apiClient.post<ApiResponse<AccountResponse>>('/api/accounts')
  if (!data.success || !data.data) throw new Error(data.error?.message || '계좌를 만들지 못했습니다.')
  return data.data
}

// JWT 소유권 검증을 거치는 계좌 상세 API를 호출한다.
export async function fetchAccount(accountId: string) {
  const { data } = await apiClient.get<ApiResponse<AccountResponse>>(`/api/accounts/${encodeURIComponent(accountId)}`)
  if (!data.success || !data.data) throw new Error(data.error?.message || '계좌 정보를 불러오지 못했습니다.')
  return data.data
}

// 명시적인 업무 명령으로 본인 계좌를 일시정지한다.
export async function suspendAccount(accountId: string) {
  return changeAccountStatus(accountId, 'suspend')
}

// 명시적인 업무 명령으로 본인 계좌를 다시 활성화한다.
export async function activateAccount(accountId: string) {
  return changeAccountStatus(accountId, 'activate')
}

// 명시적인 업무 명령으로 잔액 없는 본인 계좌를 해지한다.
export async function closeAccount(accountId: string) {
  return changeAccountStatus(accountId, 'close')
}

// 계좌 상태 명령 API의 공통 응답을 검증해 반환한다.
async function changeAccountStatus(accountId: string, command: 'suspend' | 'activate' | 'close') {
  const { data } = await apiClient.post<ApiResponse<AccountStatusResponse>>(
    `/api/accounts/${encodeURIComponent(accountId)}/${command}`,
  )
  if (!data.success || !data.data) throw new Error(data.error?.message || '계좌 상태를 변경하지 못했습니다.')
  return data.data
}
