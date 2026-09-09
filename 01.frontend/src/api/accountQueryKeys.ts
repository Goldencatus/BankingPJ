// 계좌 목록 캐시를 식별하는 공통 Query Key를 반환한다.
export function accountsQueryKey() {
  return ['accounts'] as const
}

// 계좌 ID별 상세 캐시를 목록 캐시 아래에서 식별한다.
export function accountDetailQueryKey(accountId: string) {
  return [...accountsQueryKey(), 'detail', accountId] as const
}

// 모든 계좌 거래내역 캐시의 공통 접두사를 반환한다.
export function transactionsQueryKey() {
  return ['transactions'] as const
}

// 한 계좌의 모든 페이지 거래내역 캐시 접두사를 반환한다.
export function accountTransactionsQueryKey(accountId: string) {
  return [...transactionsQueryKey(), accountId] as const
}

// 계좌·페이지·크기별 거래내역 Server State를 식별한다.
export function transactionPageQueryKey(accountId: string, page: number, size: number) {
  return [...accountTransactionsQueryKey(accountId), page, size] as const
}
