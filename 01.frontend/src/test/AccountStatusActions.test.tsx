import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AccountStatusActions } from '../components/AccountStatusActions'
import type { AccountStatus } from '../types/account'

const statusMocks = vi.hoisted(() => ({ suspendAccount: vi.fn(), activateAccount: vi.fn(), closeAccount: vi.fn() }))
vi.mock('../api/accountApi', () => statusMocks)

// 상태 관리 Component에 독립 Query Cache를 제공하고 invalidation 호출을 관찰한다.
function renderActions(status: AccountStatus) {
  const queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
  const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
  render(<QueryClientProvider client={queryClient}><AccountStatusActions accountId="7" status={status} /></QueryClientProvider>)
  return { invalidate }
}

// 상태 변경 버튼을 누르고 확인 Dialog에서 명령을 확정한다.
async function confirmAction(buttonName: string) {
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: buttonName }))
  await user.click(screen.getByRole('button', { name: '확인' }))
  return user
}

describe('계좌 상태 관리 UI', () => {
  beforeEach(() => {
    statusMocks.suspendAccount.mockReset()
    statusMocks.activateAccount.mockReset()
    statusMocks.closeAccount.mockReset()
  })

  // ACTIVE 상태에서 허용되는 일시정지와 해지 명령만 노출하는지 검증한다.
  it('ACTIVE 계좌에는 일시정지와 해지 버튼을 표시한다', () => {
    renderActions('ACTIVE')
    expect(screen.getByRole('button', { name: '계좌 일시정지' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '계좌 해지' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: '다시 사용' })).not.toBeInTheDocument()
  })

  // SUSPENDED 상태에서 재활성화와 해지 명령을 노출하는지 검증한다.
  it('SUSPENDED 계좌에는 다시 사용과 해지 버튼을 표시한다', () => {
    renderActions('SUSPENDED')
    expect(screen.getByRole('button', { name: '다시 사용' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '계좌 해지' })).toBeEnabled()
  })

  // CLOSED 상태는 안내만 표시하고 모든 상태 변경 버튼을 숨기는지 검증한다.
  it('CLOSED 계좌에는 상태 변경 버튼을 표시하지 않는다', () => {
    renderActions('CLOSED')
    expect(screen.getByRole('note')).toHaveTextContent('해지된 계좌입니다.')
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  // 일시정지 확인 후 API 호출과 계좌 목록·상세 캐시 갱신을 검증한다.
  it('확인 후 Suspend Mutation을 실행하고 계좌 Query를 갱신한다', async () => {
    statusMocks.suspendAccount.mockResolvedValue({ accountId: 7, status: 'SUSPENDED', updatedAt: '2026-09-09T10:00:00' })
    const { invalidate } = renderActions('ACTIVE')
    await confirmAction('계좌 일시정지')
    expect(statusMocks.suspendAccount).toHaveBeenCalledWith('7')
    expect(await screen.findByText('계좌 상태가 변경되었습니다.')).toBeInTheDocument()
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['accounts'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['accounts', 'detail', '7'] })
  })

  // 재사용 확인 후 Activate API에 정확한 계좌 ID를 전달하는지 검증한다.
  it('확인 후 Activate Mutation을 실행한다', async () => {
    statusMocks.activateAccount.mockResolvedValue({ accountId: 7, status: 'ACTIVE', updatedAt: '2026-09-09T10:00:00' })
    renderActions('SUSPENDED')
    await confirmAction('다시 사용')
    expect(statusMocks.activateAccount).toHaveBeenCalledWith('7')
  })

  // 해지 API 호출 전에 비가역성을 알리는 확인 Dialog가 표시되는지 검증한다.
  it('Close 전에 되돌릴 수 없다는 확인 문구를 표시한다', async () => {
    renderActions('ACTIVE')
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '계좌 해지' }))
    expect(screen.getByRole('dialog')).toHaveTextContent('해지된 계좌는 다시 활성화할 수 없습니다.')
    expect(statusMocks.closeAccount).not.toHaveBeenCalled()
  })

  // 잔액이 남은 계좌의 해지 실패 메시지를 사용자에게 표시하는지 검증한다.
  it('Close 실패 Business Error를 표시한다', async () => {
    statusMocks.closeAccount.mockRejectedValue({ isAxiosError: true, response: { data: { success: false, data: null, error: { code: 'ACCOUNT_005', message: '잔액이 있는 계좌는 해지할 수 없습니다.' } } } })
    renderActions('ACTIVE')
    await confirmAction('계좌 해지')
    expect(await screen.findByRole('alert')).toHaveTextContent('잔액이 있는 계좌는 해지할 수 없습니다.')
  })
})
