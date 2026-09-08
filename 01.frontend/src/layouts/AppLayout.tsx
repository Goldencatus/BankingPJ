import { useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'

const navigation = [
  { to: '/dashboard', label: 'Dashboard' },
  { to: '/accounts', label: 'Accounts' },
  { to: '/transfer', label: 'Transfer' },
  { to: '/transactions', label: 'Transactions' },
]

// 인증된 화면의 공통 탐색과 로그아웃 동작을 제공한다.
export function AppLayout() {
  const auth = useAuth()
  const navigate = useNavigate()
  const [loggingOut, setLoggingOut] = useState(false)

  // 로그아웃 처리 후 공개 로그인 화면으로 이동한다.
  const handleLogout = async () => {
    setLoggingOut(true)
    try {
      await auth.logout()
    } catch {
      // 서버 요청이 실패해도 AuthProvider가 브라우저 인증 상태를 제거한다.
    } finally {
      navigate('/login', { replace: true })
    }
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <NavLink className="brand" to="/dashboard"><span className="brand-mark">B</span><span>BankingPJ</span></NavLink>
        <nav aria-label="주요 메뉴">
          {navigation.map((item) => <NavLink key={item.to} to={item.to} className={({ isActive }) => isActive ? 'nav-link active' : 'nav-link'}>{item.label}</NavLink>)}
        </nav>
        <button className="logout-button" type="button" onClick={handleLogout} disabled={loggingOut}>{loggingOut ? '로그아웃 중...' : 'Logout'}</button>
      </aside>
      <main className="app-content"><Outlet /></main>
    </div>
  )
}
