import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { fetchAccounts } from '../api/accountApi'
import { accountsQueryKey } from '../api/accountQueryKeys'
import { apiErrorMessage } from '../api/client'
import { currentUserQueryKey, fetchCurrentUser } from '../api/userApi'
import { AccountCard } from '../components/AccountCard'
import { EmptyState, ErrorState, LoadingState } from '../components/AsyncState'
import type { AccountResponse, AccountStatus } from '../types/account'

const quickActions = [
  { to: '/accounts', label: 'Accounts', description: '내 계좌를 조회하고 관리합니다.' },
  { to: '/transfer', label: 'Transfer', description: 'ACTIVE 계좌에서 안전하게 이체합니다.' },
  { to: '/transactions', label: 'Transactions', description: '계좌별 원장 거래내역을 확인합니다.' },
]

// 계좌 목록에서 전체 및 상태별 개수를 정확한 정수로 계산한다.
function accountSummary(accounts: AccountResponse[]) {
  const counts: Record<AccountStatus, number> = { ACTIVE: 0, SUSPENDED: 0, CLOSED: 0 }
  accounts.forEach((account) => { counts[account.status] += 1 })
  return { total: accounts.length, ...counts }
}

// 현재 사용자와 계좌 Query를 조합해 로그인 후 메인 화면을 제공한다.
export function DashboardPage() {
  const currentUser = useQuery({ queryKey: currentUserQueryKey(), queryFn: fetchCurrentUser })
  const accounts = useQuery({ queryKey: accountsQueryKey(), queryFn: fetchAccounts })
  const pending = currentUser.isPending || accounts.isPending
  const failed = currentUser.isError || accounts.isError

  return <section className="page-stack dashboard-page">
    <p className="eyebrow">OVERVIEW</p><h1>Dashboard</h1>
    {currentUser.isSuccess && <div className="dashboard-welcome"><div><span>로그인 사용자</span><h2>{currentUser.data.name}님</h2><p>{currentUser.data.role === 'USER' ? '일반 사용자' : currentUser.data.role}</p></div><span className="user-role-badge">{currentUser.data.role}</span></div>}

    <nav className="quick-actions" aria-label="빠른 작업">
      {quickActions.map((action) => <Link key={action.to} to={action.to}><strong>{action.label}</strong><span>{action.description}</span><b aria-hidden="true">→</b></Link>)}
    </nav>

    {pending && !failed && <LoadingState title="Dashboard를 불러오는 중입니다." description="사용자와 계좌 정보를 확인하고 있습니다." />}
    {failed && <ErrorState title="Dashboard를 불러오지 못했습니다." description={apiErrorMessage(currentUser.error ?? accounts.error, '잠시 후 다시 시도해 주세요.')}><button className="secondary-button" type="button" onClick={() => void Promise.all([currentUser.refetch(), accounts.refetch()])}>다시 시도</button></ErrorState>}
    {currentUser.isSuccess && accounts.isSuccess && <DashboardAccounts accounts={accounts.data} />}
  </section>
}

// 보유 계좌 수와 상태 요약, 주요 계좌 카드를 표시한다.
function DashboardAccounts({ accounts }: { accounts: AccountResponse[] }) {
  const summary = accountSummary(accounts)
  return <>
    <section className="dashboard-summary" aria-label="계좌 상태 요약">
      <article><span>보유 계좌</span><strong>{summary.total}개</strong></article>
      <article><span>정상</span><strong>{summary.ACTIVE}개</strong></article>
      <article><span>일시정지</span><strong>{summary.SUSPENDED}개</strong></article>
      <article><span>해지</span><strong>{summary.CLOSED}개</strong></article>
    </section>
    {accounts.length === 0
      ? <EmptyState title="아직 계좌가 없습니다." description="첫 계좌를 만들고 BankingPJ를 시작해 보세요."><Link className="secondary-button" to="/accounts">계좌 만들기</Link></EmptyState>
      : <section className="dashboard-accounts"><div className="section-heading"><h2>주요 계좌</h2><Link to="/accounts">전체 계좌 보기 →</Link></div><div className="account-grid compact-grid">{accounts.slice(0, 3).map((account) => <AccountCard key={account.accountId} account={account} />)}</div></section>}
  </>
}
