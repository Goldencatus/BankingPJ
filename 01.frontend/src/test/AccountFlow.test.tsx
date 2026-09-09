import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import { AuthContext, type AuthContextValue } from '../auth/AuthContext'
import { AccountDetailPage } from '../pages/AccountDetailPage'
import { AccountsPage } from '../pages/AccountsPage'
import { DashboardPage } from '../pages/DashboardPage'
import type { AccountResponse } from '../types/account'

const accountMocks = vi.hoisted(() => ({
  fetchAccounts: vi.fn(),
  createAccount: vi.fn(),
  fetchAccount: vi.fn(),
  suspendAccount: vi.fn(),
  activateAccount: vi.fn(),
  closeAccount: vi.fn(),
}))
const userMocks = vi.hoisted(() => ({ fetchCurrentUser: vi.fn() }))

vi.mock('../api/accountApi', () => accountMocks)
vi.mock('../api/userApi', () => ({ fetchCurrentUser: userMocks.fetchCurrentUser, currentUserQueryKey: () => ['current-user'] }))

const activeAccount: AccountResponse = {
  accountId: 1,
  accountNumber: '12345678901234',
  balance: '1000.2500',
  status: 'ACTIVE',
  createdAt: '2026-09-09T10:20:30',
}

const suspendedAccount: AccountResponse = {
  accountId: 2,
  accountNumber: '98765432109876',
  balance: '50.0000',
  status: 'SUSPENDED',
  createdAt: '2026-09-09T11:20:30',
}

// 각 테스트마다 독립된 Query Cache와 브라우저 경로로 화면을 렌더링한다.
function renderWithQuery(ui: React.ReactNode, initialEntry = '/') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={[initialEntry]}>{ui}</MemoryRouter></QueryClientProvider>)
}

// URL Parameter가 필요한 화면을 실제 Route Pattern과 함께 렌더링한다.
function renderRoute(ui: React.ReactNode, initialEntry: string, path: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={[initialEntry]}><Routes><Route path={path} element={ui} /></Routes></MemoryRouter></QueryClientProvider>)
}

// Backend 공통 ErrorCode를 가진 Axios 형태 오류를 화면 테스트에 제공한다.
function apiError(code: string, message: string) {
  return { isAxiosError: true, response: { data: { success: false, data: null, error: { code, message } } } }
}

// 보호 경로 검증에 사용할 최소 인증 Context 값을 생성한다.
function authValue(authenticated: boolean): AuthContextValue {
  return {
    accessToken: authenticated ? 'test-access-token' : null,
    authenticated,
    initialized: true,
    login: vi.fn(),
    logout: vi.fn(),
    bootstrap: vi.fn(),
  }
}

describe('Frontend Account 기능', () => {
  beforeEach(() => {
    accountMocks.fetchAccounts.mockReset()
    accountMocks.createAccount.mockReset()
    accountMocks.fetchAccount.mockReset()
    userMocks.fetchCurrentUser.mockReset()
    userMocks.fetchCurrentUser.mockResolvedValue({ userId: 42, name: '홍길동', role: 'USER' })
  })

  it('Account 목록에 계좌번호, 잔액, 상태를 렌더링한다', async () => {
    accountMocks.fetchAccounts.mockResolvedValue([activeAccount, suspendedAccount])
    renderWithQuery(<AccountsPage />)
    expect(await screen.findByText('12345678901234')).toBeInTheDocument()
    expect(screen.getByText('1,000.25원')).toBeInTheDocument()
    expect(screen.getByText('SUSPENDED · 일시정지')).toBeInTheDocument()
  })

  it('Account 목록 요청 중 Loading 상태를 표시한다', () => {
    accountMocks.fetchAccounts.mockImplementation(() => new Promise(() => undefined))
    renderWithQuery(<AccountsPage />)
    expect(screen.getByRole('status')).toHaveTextContent('계좌를 불러오는 중입니다.')
  })

  it('Account가 없으면 Empty 상태를 표시한다', async () => {
    accountMocks.fetchAccounts.mockResolvedValue([])
    renderWithQuery(<AccountsPage />)
    expect(await screen.findByText('아직 계좌가 없습니다.')).toBeInTheDocument()
  })

  it('Account API 오류와 다시 시도 동작을 표시한다', async () => {
    accountMocks.fetchAccounts.mockRejectedValue(new Error('network detail'))
    renderWithQuery(<AccountsPage />)
    expect(await screen.findByRole('alert')).toHaveTextContent('잠시 후 다시 시도해 주세요.')
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeEnabled()
  })

  it('Account 생성 성공 후 목록 Query를 갱신한다', async () => {
    const created = { ...activeAccount, accountId: 3, accountNumber: '22223333444455' }
    accountMocks.fetchAccounts.mockResolvedValueOnce([]).mockResolvedValueOnce([created])
    accountMocks.createAccount.mockResolvedValue(created)
    renderWithQuery(<AccountsPage />)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '계좌 만들기' }))
    expect(await screen.findByText('22223333444455')).toBeInTheDocument()
    expect(accountMocks.fetchAccounts).toHaveBeenCalledTimes(2)
  })

  it('Account 생성 요청 중 버튼을 비활성화하여 중복 Submit을 막는다', async () => {
    accountMocks.fetchAccounts.mockResolvedValue([])
    accountMocks.createAccount.mockImplementation(() => new Promise(() => undefined))
    renderWithQuery(<AccountsPage />)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '계좌 만들기' }))
    const pendingButton = screen.getByRole('button', { name: '계좌 생성 중...' })
    expect(pendingButton).toBeDisabled()
    await user.click(pendingButton)
    expect(accountMocks.createAccount).toHaveBeenCalledTimes(1)
  })

  it('Account 상세에 Backend가 제공한 안전한 필드를 표시한다', async () => {
    accountMocks.fetchAccount.mockResolvedValue(activeAccount)
    renderRoute(<AccountDetailPage />, '/accounts/1', '/accounts/:accountId')
    expect(await screen.findByRole('heading', { name: '계좌 상세' })).toBeInTheDocument()
    expect(screen.getByText('12345678901234')).toBeInTheDocument()
    expect(screen.getByText('1,000.25원')).toBeInTheDocument()
    expect(screen.getAllByText('ACTIVE · 정상')).toHaveLength(2)
    expect(screen.getByText(/2026/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '거래내역 보기 →' })).toHaveAttribute('href', '/transactions?accountId=1')
  })

  it('존재하지 않거나 소유하지 않은 Account는 Not Found 오류로 처리한다', async () => {
    accountMocks.fetchAccount.mockRejectedValue(apiError('ACCOUNT_001', '계좌를 찾을 수 없습니다.'))
    renderRoute(<AccountDetailPage />, '/accounts/999', '/accounts/:accountId')
    expect(await screen.findByRole('alert')).toHaveTextContent('계좌를 찾을 수 없습니다.')
    expect(screen.getByRole('link', { name: '계좌 목록으로' })).toHaveAttribute('href', '/accounts')
  })

  it.each(['SUSPENDED', 'CLOSED'] as const)('%s 계좌 상세에서 입금과 출금을 제한한다', async (status) => {
    accountMocks.fetchAccount.mockResolvedValue({ ...activeAccount, status })
    renderRoute(<AccountDetailPage />, '/accounts/1', '/accounts/:accountId')
    expect(await screen.findByText(/입금·출금할 수 없습니다/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '입금하기' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '출금하기' })).toBeDisabled()
  })

  it('Dashboard에 보유 계좌 수와 계좌 일부를 표시한다', async () => {
    accountMocks.fetchAccounts.mockResolvedValue([activeAccount, suspendedAccount])
    renderWithQuery(<DashboardPage />)
    expect(await screen.findByText('2개')).toBeInTheDocument()
    expect(screen.getByText('12345678901234')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Transfer/ })).toHaveAttribute('href', '/transfer')
  })

  it('인증 실패 상태에서는 Account API를 호출하지 않고 기존 로그인 경로로 이동한다', async () => {
    renderWithQuery(<AuthContext.Provider value={authValue(false)}><App /></AuthContext.Provider>, '/accounts')
    expect(await screen.findByRole('heading', { name: '로그인' })).toBeInTheDocument()
    expect(accountMocks.fetchAccounts).not.toHaveBeenCalled()
  })
})
