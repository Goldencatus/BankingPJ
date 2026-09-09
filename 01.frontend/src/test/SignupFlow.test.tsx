import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthContext, type AuthContextValue } from '../auth/AuthContext'
import { LoginPage } from '../pages/LoginPage'
import { SignupPage } from '../pages/SignupPage'
import { PublicOnlyRoute } from '../routes/PublicOnlyRoute'
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'

const signupMocks = vi.hoisted(() => ({ signupRequest: vi.fn() }))
vi.mock('../api/authApi', () => ({ signupRequest: signupMocks.signupRequest }))

// 공개 인증 화면 테스트에 사용할 인증 상태를 생성한다.
function authValue(authenticated = false): AuthContextValue {
  return {
    accessToken: authenticated ? 'test-access-token' : null,
    authenticated,
    initialized: true,
    login: vi.fn(),
    logout: vi.fn(),
    bootstrap: vi.fn(),
  }
}

// 로그인과 회원가입 Route를 실제 공개 전용 정책과 함께 렌더링한다.
function renderAuthPages(initialEntry = '/signup', authenticated = false) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <AuthContext.Provider value={authValue(authenticated)}>
          <Routes>
            <Route element={<PublicOnlyRoute />}>
              <Route path="/login" element={<LoginPage />} />
              <Route path="/signup" element={<SignupPage />} />
            </Route>
            <Route path="/dashboard" element={<h1>Dashboard</h1>} />
          </Routes>
        </AuthContext.Provider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

// 유효한 회원가입 정보를 입력하고 제출한다.
async function submitValidSignup() {
  const user = userEvent.setup()
  await user.type(screen.getByLabelText('이메일'), 'new@example.com')
  await user.type(screen.getByLabelText('비밀번호'), 'password123!')
  await user.type(screen.getByLabelText('이름'), '홍길동')
  await user.click(screen.getByRole('button', { name: '회원가입' }))
  return user
}

describe('Frontend 회원가입 흐름', () => {
  beforeEach(() => signupMocks.signupRequest.mockReset())

  // 회원가입에 필요한 세 입력과 제출 버튼을 모두 표시하는지 검증한다.
  it('Signup Form을 렌더링한다', () => {
    renderAuthPages()
    expect(screen.getByRole('heading', { name: '회원가입' })).toBeInTheDocument()
    expect(screen.getByLabelText('이메일')).toBeInTheDocument()
    expect(screen.getByLabelText('비밀번호')).toBeInTheDocument()
    expect(screen.getByLabelText('이름')).toBeInTheDocument()
  })

  // 잘못된 이메일 형식은 API 호출 전에 차단하는지 검증한다.
  it('email 형식을 검증한다', async () => {
    renderAuthPages()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('이메일'), 'invalid-email')
    await user.type(screen.getByLabelText('비밀번호'), 'password123!')
    await user.type(screen.getByLabelText('이름'), '홍길동')
    await user.click(screen.getByRole('button', { name: '회원가입' }))
    expect(await screen.findByText('올바른 이메일 주소를 입력해 주세요.')).toBeInTheDocument()
    expect(signupMocks.signupRequest).not.toHaveBeenCalled()
  })

  // Backend 최소 길이보다 짧은 비밀번호는 요청하지 않는지 검증한다.
  it('password 최소 길이를 검증한다', async () => {
    renderAuthPages()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('이메일'), 'new@example.com')
    await user.type(screen.getByLabelText('비밀번호'), 'short')
    await user.type(screen.getByLabelText('이름'), '홍길동')
    await user.click(screen.getByRole('button', { name: '회원가입' }))
    expect(await screen.findByText('비밀번호는 8자 이상이어야 합니다.')).toBeInTheDocument()
    expect(signupMocks.signupRequest).not.toHaveBeenCalled()
  })

  // 공백만 입력한 이름은 필수값 오류로 처리하는지 검증한다.
  it('name 필수값과 공백을 검증한다', async () => {
    renderAuthPages()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('이메일'), 'new@example.com')
    await user.type(screen.getByLabelText('비밀번호'), 'password123!')
    await user.type(screen.getByLabelText('이름'), '   ')
    await user.click(screen.getByRole('button', { name: '회원가입' }))
    expect(await screen.findByText('이름을 입력해 주세요.')).toBeInTheDocument()
    expect(signupMocks.signupRequest).not.toHaveBeenCalled()
  })

  // 검증된 세 필드를 Backend 계약과 같은 구조로 전달하는지 검증한다.
  it('정상 회원가입 요청을 전송한다', async () => {
    signupMocks.signupRequest.mockResolvedValue({ userId: 2 })
    renderAuthPages()
    await submitValidSignup()
    await waitFor(() => {
      expect(signupMocks.signupRequest).toHaveBeenCalledWith({
        email: 'new@example.com',
        password: 'password123!',
        name: '홍길동',
      })
    })
  })

  // Backend USER_001 메시지를 임의 변환하지 않고 화면에 표시하는지 검증한다.
  it('중복 이메일 오류를 표시한다', async () => {
    const response: AxiosResponse = {
      data: {
        success: false,
        data: null,
        error: {
          code: 'USER_001',
          message: '이미 사용 중인 이메일',
        },
      },
      status: 409,
      statusText: 'Conflict',
      headers: {},
      config: {} as InternalAxiosRequestConfig,
    }

    const error = new AxiosError(
      '이미 사용 중인 이메일',
      'ERR_BAD_REQUEST',
      undefined,
      undefined,
      response,
    )

    signupMocks.signupRequest.mockRejectedValueOnce(error)
    renderAuthPages()
    await submitValidSignup()
    expect(await screen.findByRole('alert')).toHaveTextContent('이미 사용 중인 이메일')
  })

  // 가입 성공 후 로그인으로 이동하고 가입 이메일과 완료 메시지를 전달하는지 검증한다.
  it('성공 후 로그인 화면으로 이동해 이메일을 자동 입력한다', async () => {
    signupMocks.signupRequest.mockResolvedValue({ userId: 2 })
    renderAuthPages()
    await submitValidSignup()
    expect(await screen.findByRole('heading', { name: '로그인' })).toBeInTheDocument()
    expect(screen.getByLabelText('이메일')).toHaveValue('new@example.com')
    expect(screen.getByRole('status')).toHaveTextContent('회원가입이 완료되었습니다.')
  })

  // 로그인 화면에서 회원가입 화면으로 이동하는 링크를 검증한다.
  it('Login에서 Signup으로 이동한다', async () => {
    renderAuthPages('/login')
    await userEvent.setup().click(screen.getByRole('link', { name: '회원가입' }))
    expect(screen.getByRole('heading', { name: '회원가입' })).toBeInTheDocument()
  })

  // 회원가입 화면에서 로그인 화면으로 돌아가는 링크를 검증한다.
  it('Signup에서 Login으로 이동한다', async () => {
    renderAuthPages()
    await userEvent.setup().click(screen.getByRole('link', { name: '로그인' }))
    expect(screen.getByRole('heading', { name: '로그인' })).toBeInTheDocument()
  })

  // 인증된 사용자가 회원가입 화면에 접근하면 Dashboard로 보내는지 검증한다.
  it('로그인 사용자의 Signup 접근을 Dashboard로 이동시킨다', () => {
    renderAuthPages('/signup', true)
    expect(screen.getByRole('heading', { name: 'Dashboard' })).toBeInTheDocument()
  })
})
