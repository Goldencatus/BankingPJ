import { Navigate, Route, Routes } from 'react-router-dom'
import { AppLayout } from './layouts/AppLayout'
import { AccountDetailPage } from './pages/AccountDetailPage'
import { AccountsPage } from './pages/AccountsPage'
import { DashboardPage } from './pages/DashboardPage'
import { LoginPage } from './pages/LoginPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { TransferPage } from './pages/TransferPage'
import { TransactionsPage } from './pages/TransactionsPage'
import { SignupPage } from './pages/SignupPage'
import { UnauthorizedPage } from './pages/UnauthorizedPage'
import { ProtectedRoute } from './routes/ProtectedRoute'
import { PublicOnlyRoute } from './routes/PublicOnlyRoute'

// 공개 로그인 경로와 인증이 필요한 애플리케이션 경로를 구성한다.
export default function App() {
  return (
    <Routes>
      <Route element={<PublicOnlyRoute />}>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/signup" element={<SignupPage />} />
      </Route>
      <Route element={<ProtectedRoute />}>
        <Route element={<AppLayout />}>
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/accounts" element={<AccountsPage />} />
          <Route path="/accounts/:accountId" element={<AccountDetailPage />} />
          <Route path="/transfer" element={<TransferPage />} />
          <Route path="/transactions" element={<TransactionsPage />} />
        </Route>
      </Route>
      <Route path="/unauthorized" element={<UnauthorizedPage />} />
      <Route path="/" element={<Navigate to="/dashboard" replace />} />
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}
