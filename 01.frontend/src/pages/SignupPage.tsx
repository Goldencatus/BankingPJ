import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { Link, useNavigate } from 'react-router-dom'
import { z } from 'zod'
import { signupRequest } from '../api/authApi'
import { apiErrorMessage } from '../api/client'

const signupSchema = z.object({
  email: z.string().trim().min(1, '이메일을 입력해 주세요.').max(255, '이메일은 255자 이하여야 합니다.').pipe(z.email('올바른 이메일 주소를 입력해 주세요.')),
  password: z.string()
    .min(8, '비밀번호는 8자 이상이어야 합니다.')
    .max(72, '비밀번호는 72자 이하여야 합니다.')
    .refine((value) => value.trim().length > 0, '비밀번호를 입력해 주세요.')
    .refine((value) => new TextEncoder().encode(value).length <= 72, '비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.'),
  name: z.string().trim().min(1, '이름을 입력해 주세요.').max(100, '이름은 100자 이하여야 합니다.'),
})

type SignupForm = z.infer<typeof signupSchema>

// 회원가입 입력을 검증하고 성공한 이메일을 로그인 화면에 전달한다.
export function SignupPage() {
  const navigate = useNavigate()
  const form = useForm<SignupForm>({
    resolver: zodResolver(signupSchema),
    defaultValues: { email: '', password: '', name: '' },
  })
  const signup = useMutation({
    mutationFn: (request: SignupForm) => signupRequest(request),
    throwOnError: false,
    onSuccess: (_response, request) => navigate('/login', {
      replace: true,
      state: { signupEmail: request.email, signupSuccess: true },
    }),
  })

  // Form에는 Promise를 반환하지 않고 실패는 Mutation 오류 상태로 처리한다.
  const submitSignup = (request: SignupForm) => {
        signup.mutate(request)
  }

  return (
    <main className="login-page">
      <section className="login-intro" aria-label="서비스 소개">
        <div className="brand light"><span className="brand-mark">B</span> BankingPJ</div>
        <div><p className="eyebrow">START SECURE BANKING</p><h1>안전한 금융 생활을<br />시작하세요.</h1><p>가입 후 로그인하여 계좌와 거래를 관리할 수 있습니다.</p></div>
        <small>비밀번호는 안전하게 암호화되어 저장됩니다.</small>
      </section>
      <section className="login-panel">
        <form className="login-card" onSubmit={form.handleSubmit(submitSignup)} noValidate>
          <p className="eyebrow">CREATE ACCOUNT</p><h2>회원가입</h2><p className="form-description">BankingPJ에서 사용할 정보를 입력하세요.</p>
          <label htmlFor="signup-email">이메일</label><input id="signup-email" type="email" autoComplete="email" placeholder="name@example.com" disabled={signup.isPending} {...form.register('email')} />
          {form.formState.errors.email && <p className="field-error">{form.formState.errors.email.message}</p>}
          <label htmlFor="signup-password">비밀번호</label><input id="signup-password" type="password" autoComplete="new-password" placeholder="8자 이상 입력" disabled={signup.isPending} {...form.register('password')} />
          {form.formState.errors.password && <p className="field-error">{form.formState.errors.password.message}</p>}
          <label htmlFor="signup-name">이름</label><input id="signup-name" type="text" autoComplete="name" placeholder="이름 입력" disabled={signup.isPending} {...form.register('name')} />
          {form.formState.errors.name && <p className="field-error">{form.formState.errors.name.message}</p>}
          {signup.isError && <div className="request-error" role="alert">{apiErrorMessage(signup.error, '회원가입에 실패했습니다.')}</div>}
          <button className="primary-button" type="submit" disabled={signup.isPending}>{signup.isPending ? '가입 중...' : '회원가입'}</button>
          <p className="auth-switch">이미 계정이 있으신가요? <Link to="/login">로그인</Link></p>
        </form>
      </section>
    </main>
  )
}
