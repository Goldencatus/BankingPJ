import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createAccount, fetchAccounts } from '../api/accountApi'
import { accountsQueryKey } from '../api/accountQueryKeys'
import { apiErrorMessage } from '../api/client'
import { AccountCard } from '../components/AccountCard'
import { EmptyState, ErrorState, LoadingState } from '../components/AsyncState'

// 내 계좌 목록의 조회·생성·상태별 화면을 TanStack Query로 관리한다.
export function AccountsPage() {
  const queryClient = useQueryClient()
  const accounts = useQuery({ queryKey: accountsQueryKey(), queryFn: fetchAccounts })
  const creation = useMutation({
    mutationFn: createAccount,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: accountsQueryKey() })
    },
  })

  return (
    <section className="page-stack">
      <div className="page-heading">
        <div><p className="eyebrow">MY BANKING</p><h1>Accounts</h1><p className="page-lead">로그인한 사용자가 소유한 계좌입니다.</p></div>
        <button className="primary-button compact" type="button" disabled={creation.isPending} onClick={() => creation.mutate()}>
          {creation.isPending ? '계좌 생성 중...' : '계좌 만들기'}
        </button>
      </div>

      {creation.isError && <div className="request-error" role="alert">{apiErrorMessage(creation.error, '계좌를 만들지 못했습니다.')}</div>}
      {accounts.isPending && <LoadingState title="계좌를 불러오는 중입니다." description="잠시만 기다려 주세요." />}
      {accounts.isError && <ErrorState title="계좌 목록을 불러오지 못했습니다." description={apiErrorMessage(accounts.error, '잠시 후 다시 시도해 주세요.')}><button className="secondary-button" type="button" onClick={() => void accounts.refetch()}>다시 시도</button></ErrorState>}
      {accounts.isSuccess && accounts.data.length === 0 && <EmptyState title="아직 계좌가 없습니다." description="첫 계좌를 만들어 BankingPJ를 시작해 보세요." />}
      {accounts.isSuccess && accounts.data.length > 0 && <div className="account-grid">{accounts.data.map((account) => <AccountCard key={account.accountId} account={account} />)}</div>}
    </section>
  )
}
