import type { MoneyValue } from './account'

export type LedgerEntryType = 'CREDIT' | 'DEBIT'

export interface TransactionResponse {
  ledgerEntryId: number
  transferId: number | null
  type: LedgerEntryType
  amount: MoneyValue
  balanceAfter: MoneyValue
  createdAt: string
}

export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
}
