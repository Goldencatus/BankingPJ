import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { fetchAccounts } from '../api/accountApi'
import { accountsQueryKey, transactionPageQueryKey } from '../api/accountQueryKeys'
import { apiErrorMessage } from '../api/client'
import { fetchTransactions } from '../api/transactionApi'
import { EmptyState, ErrorState, LoadingState } from '../components/AsyncState'
import { formatCreatedAt, formatSignedWon, formatWon, transactionLabel } from '../utils/accountFormat'

const PAGE_SIZE = 20

// 계좌 선택과 Backend 0-based 페이지에 맞춘 거래내역 Server State를 표시한다.
export function TransactionsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const [page, setPage] = useState(0)
  const accounts = useQuery({ queryKey: accountsQueryKey(), queryFn: fetchAccounts })
  const requestedAccountId = searchParams.get('accountId') ?? ''
  const selectedAccountId = accounts.data?.some((account) => String(account.accountId) === requestedAccountId)
    ? requestedAccountId
    : accounts.data?.[0] ? String(accounts.data[0].accountId) : ''
  const transactions = useQuery({
    queryKey: transactionPageQueryKey(selectedAccountId, page, PAGE_SIZE),
    queryFn: () => fetchTransactions(selectedAccountId, page, PAGE_SIZE),
    enabled: Boolean(selectedAccountId),
  })

  // 계좌 선택을 URL에 반영하고 거래내역을 첫 페이지부터 조회한다.
  const selectAccount = (accountId: string) => {
    setPage(0)
    setSearchParams(accountId ? { accountId } : {})
  }

  return (
    <section className="page-stack">
      <p className="eyebrow">LEDGER</p><h1>거래내역</h1><p className="page-lead">Backend 원장에 기록된 계좌 거래를 확인하세요.</p>
      {accounts.isPending && <LoadingState title="계좌를 불러오는 중입니다." />}
      {accounts.isError && <ErrorState title="계좌를 불러오지 못했습니다." description={apiErrorMessage(accounts.error, '잠시 후 다시 시도해 주세요.')} />}
      {accounts.isSuccess && accounts.data.length === 0 && <EmptyState title="조회할 계좌가 없습니다." />}
      {accounts.isSuccess && accounts.data.length > 0 && <>
        <label className="account-selector" htmlFor="transaction-account">조회 계좌<select id="transaction-account" value={selectedAccountId} onChange={(event) => selectAccount(event.target.value)}>{accounts.data.map((account) => <option key={account.accountId} value={String(account.accountId)}>{account.accountNumber} · {formatWon(account.balance)}</option>)}</select></label>
        {transactions.isPending && <LoadingState title="거래내역을 불러오는 중입니다." />}
        {transactions.isError && <ErrorState title="거래내역을 불러오지 못했습니다." description={apiErrorMessage(transactions.error, '잠시 후 다시 시도해 주세요.')} />}
        {transactions.isSuccess && transactions.data.content.length === 0 && <EmptyState title="거래내역이 없습니다." />}
        {transactions.isSuccess && transactions.data.content.length > 0 && <div className="transaction-list">{transactions.data.content.map((item) => <article className="transaction-row" key={item.ledgerEntryId}><div><strong>{transactionLabel(item.type, item.transferId)}</strong><span>{formatCreatedAt(item.createdAt)}{item.transferId !== null ? ` · 이체 #${item.transferId}` : ''}</span></div><div className="transaction-amount"><strong className={item.type === 'CREDIT' ? 'credit' : 'debit'}>{formatSignedWon(item.amount, item.type)}</strong><span>잔액 {formatWon(item.balanceAfter)}</span></div></article>)}</div>}
        {transactions.isSuccess && <nav className="pagination" aria-label="거래내역 페이지"><button className="secondary-button" type="button" disabled={transactions.data.first || transactions.isFetching} onClick={() => setPage((current) => current - 1)}>이전</button><span>{transactions.data.page + 1}페이지 / {Math.max(transactions.data.totalPages, 1)}페이지</span><button className="secondary-button" type="button" disabled={transactions.data.last || transactions.isFetching} onClick={() => setPage((current) => current + 1)}>다음</button></nav>}
      </>}
    </section>
  )
}
