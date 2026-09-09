import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { DashboardPage } from '../pages/DashboardPage'
import type { AccountResponse } from '../types/account'

const dashboardMocks = vi.hoisted(() => ({ fetchCurrentUser: vi.fn(), fetchAccounts: vi.fn() }))
vi.mock('../api/userApi', () => ({ fetchCurrentUser: dashboardMocks.fetchCurrentUser, currentUserQueryKey: () => ['current-user'] }))
vi.mock('../api/accountApi', () => ({ fetchAccounts: dashboardMocks.fetchAccounts }))

const accounts: AccountResponse[] = [
  { accountId: 1, accountNumber: '11112222333344', balance: '123456.0000', status: 'ACTIVE', createdAt: '2026-09-09T10:00:00' },
  { accountId: 2, accountNumber: '55556666777788', balance: '0.0000', status: 'SUSPENDED', createdAt: '2026-09-09T10:00:00' },
  { accountId: 3, accountNumber: '99990000111122', balance: '0.0000', status: 'CLOSED', createdAt: '2026-09-09T10:00:00' },
]

// Dashboard에 독립 Query Cache와 Router를 제공한다.
function renderDashboard() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}><MemoryRouter><DashboardPage /></MemoryRouter></QueryClientProvider>)
}

describe('Dashboard 메인 화면', () => {
  beforeEach(() => {
    dashboardMocks.fetchCurrentUser.mockReset()
    dashboardMocks.fetchAccounts.mockReset()
    dashboardMocks.fetchCurrentUser.mockResolvedValue({ userId: 42, name: '홍길동', role: 'USER' })
    dashboardMocks.fetchAccounts.mockResolvedValue(accounts)
  })

  // /users/me가 DB에서 조회한 사용자 이름과 역할을 표시하는지 검증한다.
  it('로그인 사용자 정보를 표시한다', async () => {
    renderDashboard()
    expect(await screen.findByRole('heading', { name: '홍길동님' })).toBeInTheDocument()
    expect(screen.getByText('일반 사용자')).toBeInTheDocument()
    expect(screen.getByText('USER')).toBeInTheDocument()
  })

  // 보유 계좌와 상태별 개수, 주요 카드의 공통 표시를 검증한다.
  it('계좌 요약과 상태별 주요 계좌 카드를 표시한다', async () => {
    renderDashboard()
    expect(await screen.findByText('3개')).toBeInTheDocument()
    expect(screen.getAllByText('1개')).toHaveLength(3)
    expect(screen.getByText('123,456원')).toBeInTheDocument()
    expect(screen.getByText('ACTIVE · 정상')).toBeInTheDocument()
    expect(screen.getByText('SUSPENDED · 일시정지')).toBeInTheDocument()
    expect(screen.getByText('CLOSED · 해지')).toBeInTheDocument()
    expect(screen.getAllByText('상세보기 →')).toHaveLength(3)
  })

  // 계좌가 없을 때 0개 요약과 시작 안내를 구분해 표시하는지 검증한다.
  it('보유 계좌가 없으면 Empty 상태를 표시한다', async () => {
    dashboardMocks.fetchAccounts.mockResolvedValue([])
    renderDashboard()
    expect(await screen.findAllByText('0개')).toHaveLength(4)
    expect(screen.getByText('아직 계좌가 없습니다.')).toBeInTheDocument()
  })

  // 주요 금융 화면으로 이동하는 Quick Action 링크를 검증한다.
  it('Accounts, Transfer, Transactions Quick Action을 제공한다', () => {
    renderDashboard()
    expect(screen.getByRole('link', { name: /Accounts/ })).toHaveAttribute('href', '/accounts')
    expect(screen.getByRole('link', { name: /Transfer/ })).toHaveAttribute('href', '/transfer')
    expect(screen.getByRole('link', { name: /Transactions/ })).toHaveAttribute('href', '/transactions')
  })

  // 사용자 또는 계좌 요청이 진행 중이면 공통 Loading 상태를 표시하는지 검증한다.
  it('Dashboard 요청 중 Loading 상태를 표시한다', () => {
    dashboardMocks.fetchCurrentUser.mockImplementation(() => new Promise(() => undefined))
    renderDashboard()
    expect(screen.getByRole('status')).toHaveTextContent('Dashboard를 불러오는 중입니다.')
  })

  // API 실패 시 공통 Error 상태와 재시도 동작을 제공하는지 검증한다.
  it('Dashboard API 오류와 다시 시도를 표시한다', async () => {
    dashboardMocks.fetchAccounts.mockRejectedValue(new Error('network detail'))
    renderDashboard()
    const retry = await screen.findByRole('button', { name: '다시 시도' })
    expect(screen.getByRole('alert')).toHaveTextContent('Dashboard를 불러오지 못했습니다.')
    await userEvent.setup().click(retry)
    expect(dashboardMocks.fetchAccounts).toHaveBeenCalledTimes(2)
  })
})
