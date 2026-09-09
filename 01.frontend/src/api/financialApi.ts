import type { ApiResponse } from '../types/api'
import type { MoneyMovementResponse, TransferAction, TransferRequest, TransferResponse } from '../types/financial'
import { apiClient } from './client'

// 검증된 원 단위 정수 문자열을 Backend 금융 요청 숫자로 변환한다.
function requestAmount(amount: string) {
  return Number(amount)
}

// 본인 계좌에 입력한 금액을 입금한다.
export async function deposit(accountId: string, amount: string) {
  const { data } = await apiClient.post<ApiResponse<MoneyMovementResponse>>(
    `/api/accounts/${encodeURIComponent(accountId)}/deposits`, { amount: requestAmount(amount) },
  )
  if (!data.success || !data.data) throw new Error(data.error?.message || '입금하지 못했습니다.')
  return data.data
}

// 본인 계좌에서 입력한 금액을 출금한다.
export async function withdraw(accountId: string, amount: string) {
  const { data } = await apiClient.post<ApiResponse<MoneyMovementResponse>>(
    `/api/accounts/${encodeURIComponent(accountId)}/withdrawals`, { amount: requestAmount(amount) },
  )
  if (!data.success || !data.data) throw new Error(data.error?.message || '출금하지 못했습니다.')
  return data.data
}

// 한 번의 사용자 이체 의도에 UUID를 생성해 고정한다.
export function createTransferAction(request: TransferRequest): TransferAction {
  return { request, idempotencyKey: crypto.randomUUID() }
}

// Action에 고정된 Idempotency-Key로 이체를 요청해 Axios 재시도에도 같은 Header를 유지한다.
export async function transfer(action: TransferAction) {
  const { request, idempotencyKey } = action
  const { data } = await apiClient.post<ApiResponse<TransferResponse>>('/api/transfers', {
    fromAccountId: Number(request.fromAccountId),
    toAccountNumber: request.toAccountNumber.trim(),
    amount: requestAmount(request.amount),
  }, { headers: { 'Idempotency-Key': idempotencyKey } })
  if (!data.success || !data.data) throw new Error(data.error?.message || '이체하지 못했습니다.')
  return data.data
}
