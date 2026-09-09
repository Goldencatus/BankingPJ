import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import { AuthProvider } from '../auth/AuthProvider'
import { getAccessToken, setAccessToken } from '../auth/tokenStore'

const authMocks = vi.hoisted(() => ({
  signupRequest: vi.fn(),
  loginRequest: vi.fn(),
  logoutRequest: vi.fn(),
  refreshAccessToken: vi.fn(),
}))

vi.mock('../api/authApi', () => ({
  signupRequest: authMocks.signupRequest,
  loginRequest: authMocks.loginRequest,
  logoutRequest: authMocks.logoutRequest,
}))

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, refreshAccessToken: authMocks.refreshAccessToken }
})

// 실제 앱 Provider와 메모리 Router를 조합해 인증 화면을 렌더링한다.
function renderApp(initialEntry = '/login') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <AuthProvider><App /></AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

// 앱 시작 시 유효한 Refresh Cookie가 있는 상황을 재현한다.
function mockSuccessfulBootstrap(token = 'restored-access-token') {
  authMocks.refreshAccessToken.mockImplementation(async () => {
    setAccessToken(token)
    return token
  })
}

// 정상적인 로그인 입력을 채우고 제출한다.
async function submitValidLogin() {
  const user = userEvent.setup()
  await user.type(screen.getByLabelText('이메일'), 'user@example.com')
  await user.type(screen.getByLabelText('비밀번호'), 'password123!')
  await user.click(screen.getByRole('button', { name: '로그인' }))
  return user
}

describe('Frontend 로그인 및 인증 흐름', () => {
  beforeEach(() => {
    authMocks.signupRequest.mockReset()
    authMocks.loginRequest.mockReset()
    authMocks.logoutRequest.mockReset()
    authMocks.refreshAccessToken.mockReset()
    authMocks.refreshAccessToken.mockRejectedValue(new Error('refresh cookie 없음'))
    authMocks.logoutRequest.mockResolvedValue(undefined)
  })

  it('로그인 폼의 이메일, 비밀번호, 제출 버튼을 표시한다', async () => {
    renderApp()
    expect(await screen.findByRole('heading', { name: '로그인' })).toBeInTheDocument()
    expect(screen.getByLabelText('이메일')).toBeInTheDocument()
    expect(screen.getByLabelText('비밀번호')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '로그인' })).toBeEnabled()
  })

  it('이메일 형식이 잘못되면 API를 호출하지 않고 오류를 표시한다', async () => {
    renderApp()
    await screen.findByRole('heading', { name: '로그인' })
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('이메일'), 'invalid-email')
    await user.type(screen.getByLabelText('비밀번호'), 'password123!')
    await user.click(screen.getByRole('button', { name: '로그인' }))
    expect(await screen.findByText('올바른 이메일 주소를 입력해 주세요.')).toBeInTheDocument()
    expect(authMocks.loginRequest).not.toHaveBeenCalled()
  })

  it('비밀번호가 비어 있으면 API를 호출하지 않고 오류를 표시한다', async () => {
    renderApp()
    await screen.findByRole('heading', { name: '로그인' })
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('이메일'), 'user@example.com')
    await user.click(screen.getByRole('button', { name: '로그인' }))
    expect(await screen.findByText('비밀번호를 입력해 주세요.')).toBeInTheDocument()
    expect(authMocks.loginRequest).not.toHaveBeenCalled()
  })

  it('로그인 성공 시 Access Token을 메모리에 저장하고 Dashboard로 이동한다', async () => {
    authMocks.loginRequest.mockResolvedValue({ accessToken: 'login-access-token', tokenType: 'Bearer', expiresIn: 900 })
    renderApp()
    await screen.findByRole('heading', { name: '로그인' })
    await submitValidLogin()
    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument()
    expect(getAccessToken()).toBe('login-access-token')
    expect(localStorage).toHaveLength(0)
    expect(sessionStorage).toHaveLength(0)
  })

  it('로그인 실패 시 안전한 오류 메시지를 표시한다', async () => {
    authMocks.loginRequest.mockRejectedValue(new Error('내부 인증 상세'))
    renderApp()
    await screen.findByRole('heading', { name: '로그인' })
    await submitValidLogin()
    expect(await screen.findByRole('alert')).toHaveTextContent('로그인에 실패했습니다.')
    expect(screen.queryByText('내부 인증 상세')).not.toBeInTheDocument()
  })

  it('로그인 요청 중에는 버튼을 비활성화하고 진행 상태를 표시한다', async () => {
    authMocks.loginRequest.mockImplementation(() => new Promise(() => undefined))
    renderApp()
    await screen.findByRole('heading', { name: '로그인' })
    await submitValidLogin()
    expect(screen.getByRole('button', { name: '로그인 중...' })).toBeDisabled()
  })

  it('비인증 사용자가 보호 경로에 접근하면 로그인 화면으로 보낸다', async () => {
    renderApp('/accounts')
    expect(await screen.findByRole('heading', { name: '로그인' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Accounts' })).not.toBeInTheDocument()
  })

  it('Refresh 복구에 성공한 사용자는 보호 경로에 접근할 수 있다', async () => {
    mockSuccessfulBootstrap()
    renderApp('/accounts')
    expect(await screen.findByRole('heading', { name: 'Accounts' })).toBeInTheDocument()
    expect(getAccessToken()).toBe('restored-access-token')
  })

  it('앱 시작 시 Refresh 복구에 성공하면 로그인 화면 대신 Dashboard를 표시한다', async () => {
    mockSuccessfulBootstrap('bootstrap-token')
    renderApp('/login')
    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument()
    expect(authMocks.refreshAccessToken).toHaveBeenCalledTimes(1)
  })

  it('앱 시작 시 Refresh 복구가 실패하면 이전 메모리 토큰을 제거한다', async () => {
    setAccessToken('stale-token')
    renderApp('/login')
    expect(await screen.findByRole('heading', { name: '로그인' })).toBeInTheDocument()
    expect(getAccessToken()).toBeNull()
  })

  it('로그아웃 시 서버에 요청하고 메모리 토큰을 제거한 뒤 로그인 화면으로 이동한다', async () => {
    mockSuccessfulBootstrap()
    renderApp('/dashboard')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Logout' }))
    expect(await screen.findByRole('heading', { name: '로그인' })).toBeInTheDocument()
    expect(authMocks.logoutRequest).toHaveBeenCalledTimes(1)
    expect(getAccessToken()).toBeNull()
  })
})
