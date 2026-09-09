import { Link } from 'react-router-dom'
import type { AccountResponse } from '../types/account'
import { accountStatusLabel, formatWon } from '../utils/accountFormat'

interface AccountCardProps {
  account: AccountResponse
}

// 계좌 핵심 정보와 상세 화면 링크를 일관된 카드로 표시한다.
export function AccountCard({ account }: AccountCardProps) {
  return (
    <Link className="account-card" to={`/accounts/${account.accountId}`} aria-label={`${account.accountNumber} 계좌 상세`}>
      <div className="account-card-heading">
        <span className={`status-badge status-${account.status.toLowerCase()}`}>{accountStatusLabel(account.status)}</span>
        <span aria-hidden="true">→</span>
      </div>
      <span className="account-number">{account.accountNumber}</span>
      <strong className="account-balance">{formatWon(account.balance)}</strong>
      <span className="account-detail-link">상세보기 →</span>
    </Link>
  )
}
