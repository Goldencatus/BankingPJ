import type { ApiResponse } from '../types/api'
import type { PageResponse, TransactionResponse } from '../types/transaction'
import { apiClient } from './client'

// 한 계좌의 거래내역 한 페이지를 Backend의 0-based 계약으로 조회한다.
export async function fetchTransactions(accountId: string, page: number, size: number) {
  const { data } = await apiClient.get<ApiResponse<PageResponse<TransactionResponse>>>(
    `/api/accounts/${encodeURIComponent(accountId)}/transactions`, { params: { page, size } },
  )
  if (!data.success || !data.data) throw new Error(data.error?.message || '거래내역을 불러오지 못했습니다.')
  return data.data
}
