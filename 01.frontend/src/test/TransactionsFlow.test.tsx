import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { TransactionsPage } from '../pages/TransactionsPage'
import type { AccountResponse } from '../types/account'
import type { PageResponse, TransactionResponse } from '../types/transaction'

const transactionMocks = vi.hoisted(() => ({ fetchAccounts: vi.fn(), fetchTransactions: vi.fn() }))
vi.mock('../api/accountApi', () => ({ fetchAccounts: transactionMocks.fetchAccounts }))
vi.mock('../api/transactionApi', () => ({ fetchTransactions: transactionMocks.fetchTransactions }))

const accounts: AccountResponse[] = [
  { accountId: 1, accountNumber: '11112222333344', balance: '70000.0000', status: 'ACTIVE', createdAt: '2026-09-09T10:00:00' },
  { accountId: 2, accountNumber: '55556666777788', balance: '5000.0000', status: 'CLOSED', createdAt: '2026-09-09T10:00:00' },
]
const ledger: TransactionResponse[] = [
  { ledgerEntryId: 1, transferId: null, type: 'CREDIT', amount: '100000.0000', balanceAfter: '100000.0000', createdAt: '2026-09-09T10:00:00' },
  { ledgerEntryId: 2, transferId: null, type: 'DEBIT', amount: '-10000.0000', balanceAfter: '90000.0000', createdAt: '2026-09-09T11:00:00' },
  { ledgerEntryId: 3, transferId: 31, type: 'DEBIT', amount: '-30000.0000', balanceAfter: '60000.0000', createdAt: '2026-09-09T12:00:00' },
  { ledgerEntryId: 4, transferId: 32, type: 'CREDIT', amount: '10000.0000', balanceAfter: '70000.0000', createdAt: '2026-09-09T13:00:00' },
]

// Backend PageResponse 형태의 거래내역 테스트 데이터를 생성한다.
function page(content: TransactionResponse[], number = 0, totalPages = 1): PageResponse<TransactionResponse> {
  return { content, page: number, size: 20, totalElements: content.length, totalPages, first: number === 0, last: number === totalPages - 1 }
}

// URL Query Parameter와 독립 Query Cache를 포함해 거래내역 화면을 렌더링한다.
function renderTransactions(initialEntry = '/transactions?accountId=1') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={[initialEntry]}><TransactionsPage /></MemoryRouter></QueryClientProvider>)
}

describe('거래내역 화면', () => {
  beforeEach(() => {
    transactionMocks.fetchAccounts.mockReset()
    transactionMocks.fetchTransactions.mockReset()
    transactionMocks.fetchAccounts.mockResolvedValue(accounts)
  })

  // 원장 유형과 transferId 조합에 맞는 거래명·금액·잔액 표시를 검증한다.
  it('원장 유형과 transferId로 입금·출금·이체 거래를 구분해 표시한다', async () => {
    transactionMocks.fetchTransactions.mockResolvedValue(page(ledger))
    renderTransactions()
    expect(await screen.findByText('입금')).toBeInTheDocument()
    expect(screen.getByText('출금')).toBeInTheDocument()
    expect(screen.getByText('이체 출금')).toBeInTheDocument()
    expect(screen.getByText('이체 입금')).toBeInTheDocument()
    expect(screen.getByText('+100,000원')).toBeInTheDocument()
    expect(screen.getByText('-30,000원')).toBeInTheDocument()
    expect(screen.getByText('잔액 70,000원')).toBeInTheDocument()
  })

  // 선택한 계좌의 원장이 비어 있을 때 거래내역 Empty 상태를 검증한다.
  it('선택 계좌에 Ledger가 없으면 Empty 상태를 표시한다', async () => {
    transactionMocks.fetchTransactions.mockResolvedValue(page([]))
    renderTransactions()
    expect(await screen.findByText('거래내역이 없습니다.')).toBeInTheDocument()
  })

  // 거래내역 응답 대기 중 Loading 안내를 표시하는지 검증한다.
  it('거래내역 요청 중 Loading 상태를 표시한다', async () => {
    transactionMocks.fetchTransactions.mockImplementation(() => new Promise(() => undefined))
    renderTransactions()
    expect(await screen.findByText('거래내역을 불러오는 중입니다.')).toBeInTheDocument()
  })

  // 거래내역 API 실패 시 Error 상태를 표시하는지 검증한다.
  it('거래내역 API Error 상태를 표시한다', async () => {
    transactionMocks.fetchTransactions.mockRejectedValue(new Error('network detail'))
    renderTransactions()
    expect(await screen.findByRole('alert')).toHaveTextContent('거래내역을 불러오지 못했습니다.')
  })

  // 이전·다음 버튼이 Backend의 0-based page 값을 정확히 변경하는지 검증한다.
  it('다음과 이전 버튼으로 Backend의 0-based 페이지를 변경한다', async () => {
    transactionMocks.fetchTransactions.mockImplementation((_accountId, requestedPage) => Promise.resolve(page(ledger, requestedPage, 2)))
    renderTransactions()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '다음' }))
    expect(await screen.findByText('2페이지 / 2페이지')).toBeInTheDocument()
    expect(transactionMocks.fetchTransactions).toHaveBeenCalledWith('1', 1, 20)
    await user.click(screen.getByRole('button', { name: '이전' }))
    expect(await screen.findByText('1페이지 / 2페이지')).toBeInTheDocument()
  })

  // 조회 계좌를 바꾸면 첫 페이지를 새 계좌 ID로 요청하는지 검증한다.
  it('계좌 선택을 변경하면 새 계좌 ID의 Query를 조회한다', async () => {
    transactionMocks.fetchTransactions.mockResolvedValue(page([]))
    renderTransactions()
    const user = userEvent.setup()
    await screen.findByText('거래내역이 없습니다.')
    await user.selectOptions(screen.getByLabelText('조회 계좌'), '2')
    expect(transactionMocks.fetchTransactions).toHaveBeenCalledWith('2', 0, 20)
  })
})
