import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { FullPageStatus } from '../components/FullPageStatus'

// 인증 복구가 끝날 때까지 기다리고 비인증 사용자를 로그인 화면으로 보낸다.
export function ProtectedRoute() {
  const auth = useAuth()
  const location = useLocation()

  if (!auth.initialized) {
    return <FullPageStatus title="인증 확인 중" message="안전한 세션을 복구하고 있습니다." />
  }
  if (!auth.authenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  return <Outlet />
}
