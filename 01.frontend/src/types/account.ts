export type AccountStatus = 'ACTIVE' | 'SUSPENDED' | 'CLOSED'
export type MoneyValue = string | number

// Backend AccountCreateResponse와 동일한 안전한 계좌 응답 필드만 표현한다.
export interface AccountResponse {
  accountId: number
  accountNumber: string
  balance: MoneyValue
  status: AccountStatus
  createdAt: string
}

export interface AccountStatusResponse {
  accountId: number
  status: AccountStatus
  updatedAt: string
}
