import { Navigate, Outlet } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { FullPageStatus } from '../components/FullPageStatus'

// 인증 초기화 후 로그인된 사용자가 로그인 화면으로 되돌아가지 않게 한다.
export function PublicOnlyRoute() {
  const auth = useAuth()
  if (!auth.initialized) {
    return <FullPageStatus title="인증 확인 중" message="잠시만 기다려 주세요." />
  }
  return auth.authenticated ? <Navigate to="/dashboard" replace /> : <Outlet />
}
