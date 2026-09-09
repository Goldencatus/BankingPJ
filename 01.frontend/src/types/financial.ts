import type { MoneyValue } from './account'

export interface MoneyMovementResponse {
  accountId: number
  amount: MoneyValue
  balanceAfter: MoneyValue
  createdAt: string
}

export interface TransferRequest {
  fromAccountId: string
  toAccountNumber: string
  amount: string
}

export interface TransferResponse {
  transferId: number
  fromAccountId: number
  toAccountNumber: string
  amount: MoneyValue
  status: string
  fromBalanceAfter: MoneyValue
  completedAt: string
}

export interface TransferAction {
  request: TransferRequest
  idempotencyKey: string
}
