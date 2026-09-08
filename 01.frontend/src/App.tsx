import { Navigate, Route, Routes } from 'react-router-dom'
import { AppLayout } from './layouts/AppLayout'
import { DashboardPage } from './pages/DashboardPage'
import { LoginPage } from './pages/LoginPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { PlaceholderPage } from './pages/PlaceholderPage'
import { UnauthorizedPage } from './pages/UnauthorizedPage'
import { ProtectedRoute } from './routes/ProtectedRoute'
import { PublicOnlyRoute } from './routes/PublicOnlyRoute'

// 공개 로그인 경로와 인증이 필요한 애플리케이션 경로를 구성한다.
export default function App() {
  return (
    <Routes>
      <Route element={<PublicOnlyRoute />}><Route path="/login" element={<LoginPage />} /></Route>
      <Route element={<ProtectedRoute />}>
        <Route element={<AppLayout />}>
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/accounts" element={<PlaceholderPage title="Accounts" description="계좌 목록 API를 연결할 화면입니다." />} />
          <Route path="/accounts/:accountId" element={<PlaceholderPage title="Account Detail" description="계좌 상세와 거래내역을 연결할 화면입니다." />} />
          <Route path="/transfer" element={<PlaceholderPage title="Transfer" description="멱등성 이체 폼을 연결할 화면입니다." />} />
          <Route path="/transactions" element={<PlaceholderPage title="Transactions" description="원장 기반 거래내역을 연결할 화면입니다." />} />
        </Route>
      </Route>
      <Route path="/unauthorized" element={<UnauthorizedPage />} />
      <Route path="/" element={<Navigate to="/dashboard" replace />} />
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}
