import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AccountMoneyForm } from '../components/AccountMoneyForm'
import { TransferPage } from '../pages/TransferPage'
import type { AccountResponse } from '../types/account'

const financialMocks = vi.hoisted(() => ({
  deposit: vi.fn(),
  withdraw: vi.fn(),
  createTransferAction: vi.fn(),
  transfer: vi.fn(),
}))
const accountMocks = vi.hoisted(() => ({ fetchAccounts: vi.fn() }))

vi.mock('../api/financialApi', () => financialMocks)
vi.mock('../api/accountApi', () => ({ fetchAccounts: accountMocks.fetchAccounts }))

const activeAccount: AccountResponse = { accountId: 7, accountNumber: '12345678901234', balance: '100000.0000', status: 'ACTIVE', createdAt: '2026-09-09T10:00:00' }
const movementResponse = { accountId: 7, amount: '10000.0000', balanceAfter: '110000.0000', createdAt: '2026-09-09T11:00:00' }
const transferResponse = { transferId: 44, fromAccountId: 7, toAccountNumber: '99998888777766', amount: '30000.0000', status: 'COMPLETED', fromBalanceAfter: '70000.0000', completedAt: '2026-09-09T12:00:00' }

// 금융 화면마다 독립된 Query Cache와 Router를 제공한다.
function renderFinancial(ui: React.ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
  render(<QueryClientProvider client={queryClient}><MemoryRouter>{ui}</MemoryRouter></QueryClientProvider>)
  return { invalidate }
}

// 입금 또는 출금 폼에 금액을 입력하고 제출한다.
async function submitMoneyForm(kind: 'deposit' | 'withdrawal', amount: string) {
  const user = userEvent.setup()
  await user.type(screen.getByLabelText('금액'), amount)
  await user.click(screen.getByRole('button', { name: kind === 'deposit' ? '입금하기' : '출금하기' }))
  return user
}

// 이체 폼의 출금 계좌, 받는 계좌, 금액을 채우고 제출한다.
async function submitTransfer() {
  const user = userEvent.setup()
  await user.selectOptions(screen.getByLabelText('출금 계좌'), '7')
  await user.type(screen.getByLabelText('받는 계좌번호'), '99998888777766')
  await user.type(screen.getByLabelText('이체 금액'), '30000')
  await user.click(screen.getByRole('button', { name: '이체하기' }))
  return user
}

describe('입금과 출금 UI', () => {
  beforeEach(() => {
    financialMocks.deposit.mockReset()
    financialMocks.withdraw.mockReset()
  })

  it('정상 입금 요청 후 목록과 상세 Query를 invalidate한다', async () => {
    financialMocks.deposit.mockResolvedValue(movementResponse)
    const { invalidate } = renderFinancial(<AccountMoneyForm accountId="7" kind="deposit" disabled={false} />)
    await submitMoneyForm('deposit', '10000')
    expect(financialMocks.deposit).toHaveBeenCalledWith('7', '10000')
    expect(await screen.findByText('입금 완료')).toBeInTheDocument()
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['accounts'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['accounts', 'detail', '7'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['transactions', '7'] })
  })

  it('입금 요청 중 버튼을 비활성화한다', async () => {
    financialMocks.deposit.mockImplementation(() => new Promise(() => undefined))
    renderFinancial(<AccountMoneyForm accountId="7" kind="deposit" disabled={false} />)
    await submitMoneyForm('deposit', '10000')
    expect(screen.getByRole('button', { name: '입금 중...' })).toBeDisabled()
  })

  it.each(['', '0', '-1', '1.5', 'abc'])('잘못된 입금액 %s 요청을 차단한다', async (amount) => {
    renderFinancial(<AccountMoneyForm accountId="7" kind="deposit" disabled={false} />)
    const user = userEvent.setup()
    if (amount) await user.type(screen.getByLabelText('금액'), amount)
    await user.click(screen.getByRole('button', { name: '입금하기' }))
    expect(await screen.findByText(/금액을 입력|원 단위 정수/)).toBeInTheDocument()
    expect(financialMocks.deposit).not.toHaveBeenCalled()
  })

  it('정상 출금 요청 후 목록과 상세 Query를 invalidate한다', async () => {
    financialMocks.withdraw.mockResolvedValue({ ...movementResponse, balanceAfter: '90000.0000' })
    const { invalidate } = renderFinancial(<AccountMoneyForm accountId="7" kind="withdrawal" disabled={false} />)
    await submitMoneyForm('withdrawal', '10000')
    expect(financialMocks.withdraw).toHaveBeenCalledWith('7', '10000')
    expect(await screen.findByText('출금 완료')).toBeInTheDocument()
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['accounts'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['accounts', 'detail', '7'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['transactions', '7'] })
  })

  it('잔액 부족 Backend Error를 사용자에게 표시한다', async () => {
    financialMocks.withdraw.mockRejectedValue({ isAxiosError: true, response: { data: { success: false, data: null, error: { code: 'ACCOUNT_003', message: '계좌 잔액이 부족합니다.' } } } })
    renderFinancial(<AccountMoneyForm accountId="7" kind="withdrawal" disabled={false} />)
    await submitMoneyForm('withdrawal', '10000')
    expect(await screen.findByRole('alert')).toHaveTextContent('계좌 잔액이 부족합니다.')
  })

  it('소수 출금액을 Backend 호출 전에 차단한다', async () => {
    renderFinancial(<AccountMoneyForm accountId="7" kind="withdrawal" disabled={false} />)
    await submitMoneyForm('withdrawal', '1.5')
    expect(await screen.findByText('금액은 0보다 큰 원 단위 정수여야 합니다.')).toBeInTheDocument()
    expect(financialMocks.withdraw).not.toHaveBeenCalled()
  })

  it.each(['SUSPENDED', 'CLOSED'])('%s 계좌에서는 금융 Action을 비활성화한다', (status) => {
    renderFinancial(<AccountMoneyForm accountId="7" kind="deposit" disabled={Boolean(status)} />)
    expect(screen.getByLabelText('금액')).toBeDisabled()
    expect(screen.getByRole('button', { name: '입금하기' })).toBeDisabled()
  })
})

describe('이체 UI', () => {
  beforeEach(() => {
    accountMocks.fetchAccounts.mockReset()
    financialMocks.createTransferAction.mockReset()
    financialMocks.transfer.mockReset()
    accountMocks.fetchAccounts.mockResolvedValue([activeAccount])
    financialMocks.createTransferAction.mockImplementation((request) => ({ request, idempotencyKey: 'uuid-action-1' }))
  })

  it('정상 이체 성공 결과와 금액을 표시하고 계좌 Query를 갱신한다', async () => {
    financialMocks.transfer.mockResolvedValue(transferResponse)
    const { invalidate } = renderFinancial(<TransferPage />)
    await screen.findByRole('option', { name: /12345678901234/ })
    await submitTransfer()
    expect(financialMocks.createTransferAction).toHaveBeenCalledTimes(1)
    expect(financialMocks.transfer.mock.calls[0][0]).toEqual(expect.objectContaining({ idempotencyKey: 'uuid-action-1' }))
    expect(await screen.findByRole('heading', { name: '이체 성공' })).toBeInTheDocument()
    expect(screen.getByText('30,000원')).toBeInTheDocument()
    expect(screen.getByText('44')).toBeInTheDocument()
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['accounts'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['accounts', 'detail', '7'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['transactions', '7'] })
  })

  it('이체 요청 중 중복 Submit을 막고 UUID Action을 한 번만 만든다', async () => {
    financialMocks.transfer.mockImplementation(() => new Promise(() => undefined))
    renderFinancial(<TransferPage />)
    await screen.findByRole('option', { name: /12345678901234/ })
    const user = await submitTransfer()
    const pendingButton = screen.getByRole('button', { name: '이체 처리 중...' })
    expect(pendingButton).toBeDisabled()
    await user.click(pendingButton)
    expect(financialMocks.createTransferAction).toHaveBeenCalledTimes(1)
    expect(financialMocks.transfer).toHaveBeenCalledTimes(1)
  })

  it('Backend 이체 Business Error를 그대로 표시한다', async () => {
    financialMocks.transfer.mockRejectedValue({ isAxiosError: true, response: { data: { success: false, data: null, error: { code: 'TRANSFER_001', message: '동일한 계좌로 이체할 수 없습니다.' } } } })
    renderFinancial(<TransferPage />)
    await screen.findByRole('option', { name: /12345678901234/ })
    await submitTransfer()
    expect(await screen.findByRole('alert')).toHaveTextContent('동일한 계좌로 이체할 수 없습니다.')
  })
})
