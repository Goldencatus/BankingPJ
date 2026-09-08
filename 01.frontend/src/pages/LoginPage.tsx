import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { useLocation, useNavigate } from 'react-router-dom'
import { z } from 'zod'
import { apiErrorMessage } from '../api/client'
import { useAuth } from '../auth/AuthContext'

const loginSchema = z.object({
  email: z.email('올바른 이메일 주소를 입력해 주세요.').max(255, '이메일은 255자 이하여야 합니다.'),
  password: z.string().min(1, '비밀번호를 입력해 주세요.').max(72, '비밀번호는 72자 이하여야 합니다.'),
})
type LoginForm = z.infer<typeof loginSchema>

// 입력 검증과 로그인 요청 상태를 처리하고 성공 시 원래 보호 경로로 이동한다.
export function LoginPage() {
  const auth = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const destination = (location.state as { from?: string } | null)?.from || '/dashboard'
  const form = useForm<LoginForm>({ resolver: zodResolver(loginSchema), defaultValues: { email: '', password: '' } })
  const login = useMutation({ mutationFn: auth.login, onSuccess: () => navigate(destination, { replace: true }) })

  return (
    <main className="login-page">
      <section className="login-intro" aria-label="서비스 소개">
        <div className="brand light"><span className="brand-mark">B</span> BankingPJ</div>
        <div><p className="eyebrow">SECURE PERSONAL BANKING</p><h1>내 금융의 흐름을<br />한눈에 확인하세요.</h1><p>안전한 인증과 정확한 원장 기록을 기반으로<br />계좌와 거래를 관리합니다.</p></div>
        <small>Access Token은 브라우저 메모리에만 보관됩니다.</small>
      </section>
      <section className="login-panel">
        <form className="login-card" onSubmit={form.handleSubmit((values) => login.mutate(values))} noValidate>
          <p className="eyebrow">WELCOME BACK</p><h2>로그인</h2><p className="form-description">BankingPJ 계정으로 계속하세요.</p>
          <label htmlFor="email">이메일</label><input id="email" type="email" autoComplete="email" placeholder="name@example.com" {...form.register('email')} />
          {form.formState.errors.email && <p className="field-error">{form.formState.errors.email.message}</p>}
          <label htmlFor="password">비밀번호</label><input id="password" type="password" autoComplete="current-password" placeholder="비밀번호 입력" {...form.register('password')} />
          {form.formState.errors.password && <p className="field-error">{form.formState.errors.password.message}</p>}
          {login.isError && <div className="request-error" role="alert">{apiErrorMessage(login.error, '로그인에 실패했습니다.')}</div>}
          <button className="primary-button" type="submit" disabled={login.isPending}>{login.isPending ? '로그인 중...' : '로그인'}</button>
        </form>
      </section>
    </main>
  )
}
