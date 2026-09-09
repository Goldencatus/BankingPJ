import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { fetchAccount } from '../api/accountApi'
import { accountDetailQueryKey } from '../api/accountQueryKeys'
import { apiErrorCode, apiErrorMessage } from '../api/client'
import { AccountMoneyForm } from '../components/AccountMoneyForm'
import { AccountStatusActions } from '../components/AccountStatusActions'
import { ErrorState, LoadingState } from '../components/AsyncState'
import { accountStatusLabel, formatCreatedAt, formatWon } from '../utils/accountFormat'

// URL의 계좌 ID로 소유권 검증된 상세 정보를 조회하고 표시한다.
export function AccountDetailPage() {
  const { accountId = '' } = useParams()
  const validAccountId = /^\d+$/.test(accountId) && Number(accountId) > 0
  const account = useQuery({
    queryKey: accountDetailQueryKey(accountId),
    queryFn: () => fetchAccount(accountId),
    enabled: validAccountId,
  })

  if (!validAccountId) return <AccountError title="올바르지 않은 계좌 주소입니다." message="계좌 목록에서 다시 선택해 주세요." />
  if (account.isPending) return <section className="page-stack"><LoadingState title="계좌 정보를 불러오는 중입니다." /></section>
  if (account.isError) {
    const missing = apiErrorCode(account.error) === 'ACCOUNT_001'
    return <AccountError title={missing ? '계좌를 찾을 수 없습니다.' : '계좌 정보를 불러오지 못했습니다.'} message={apiErrorMessage(account.error, '잠시 후 다시 시도해 주세요.')} />
  }

  return (
    <section className="page-stack">
      <Link className="back-link" to="/accounts">← 계좌 목록</Link>
      <p className="eyebrow">ACCOUNT DETAIL</p><h1>계좌 상세</h1>
      <article className="detail-card">
        <span className={`status-badge status-${account.data.status.toLowerCase()}`}>{accountStatusLabel(account.data.status)}</span>
        <dl>
          <div><dt>계좌번호</dt><dd>{account.data.accountNumber}</dd></div>
          <div><dt>잔액</dt><dd className="detail-balance">{formatWon(account.data.balance)}</dd></div>
          <div><dt>상태</dt><dd>{accountStatusLabel(account.data.status)}</dd></div>
          <div><dt>개설일</dt><dd>{formatCreatedAt(account.data.createdAt)}</dd></div>
        </dl>
      </article>
      <AccountStatusActions accountId={accountId} status={account.data.status} />
      <div className="section-heading transaction-heading"><h2>계좌 거래</h2><Link to={`/transactions?accountId=${accountId}`}>거래내역 보기 →</Link></div>
      {account.data.status !== 'ACTIVE' && <div className="restriction-message" role="note">이 계좌는 {accountStatusLabel(account.data.status)} 상태이므로 입금·출금할 수 없습니다.</div>}
      <div className="money-form-grid">
        <AccountMoneyForm accountId={accountId} kind="deposit" disabled={account.data.status !== 'ACTIVE'} />
        <AccountMoneyForm accountId={accountId} kind="withdrawal" disabled={account.data.status !== 'ACTIVE'} />
      </div>
    </section>
  )
}

// 잘못된 ID와 Backend 조회 오류를 계좌 목록 복귀 링크와 함께 표시한다.
function AccountError({ title, message }: { title: string; message: string }) {
  return <section className="page-stack"><ErrorState title={title} description={message}><Link className="secondary-button" to="/accounts">계좌 목록으로</Link></ErrorState></section>
}
