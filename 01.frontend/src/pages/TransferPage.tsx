import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm, useWatch } from 'react-hook-form'
import { fetchAccounts } from '../api/accountApi'
import { accountDetailQueryKey, accountTransactionsQueryKey, accountsQueryKey } from '../api/accountQueryKeys'
import { apiErrorMessage } from '../api/client'
import { EmptyState, ErrorState, LoadingState } from '../components/AsyncState'
import { createTransferAction, transfer } from '../api/financialApi'
import { formatWon } from '../utils/accountFormat'
import { transferFormSchema, type TransferFormValues } from '../validation/financialSchemas'

// ACTIVE 출금 계좌 선택과 멱등성 이체 요청의 전체 화면 상태를 관리한다.
export function TransferPage() {
  const queryClient = useQueryClient()
  const accounts = useQuery({ queryKey: accountsQueryKey(), queryFn: fetchAccounts })
  const form = useForm<TransferFormValues>({ resolver: zodResolver(transferFormSchema), defaultValues: { fromAccountId: '', toAccountNumber: '', amount: '' } })
  const transferMutation = useMutation({
    mutationFn: transfer,
    onSuccess: async (_response, action) => {
      form.reset({ fromAccountId: action.request.fromAccountId, toAccountNumber: '', amount: '' })
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: accountsQueryKey() }),
        queryClient.invalidateQueries({ queryKey: accountDetailQueryKey(action.request.fromAccountId) }),
        queryClient.invalidateQueries({ queryKey: accountTransactionsQueryKey(action.request.fromAccountId) }),
      ])
    },
  })
  const activeAccounts = accounts.data?.filter((account) => account.status === 'ACTIVE') ?? []
  const amount = useWatch({ control: form.control, name: 'amount' })
  const validPreview = /^[1-9]\d*$/.test(amount) && amount.length <= 15

  // 유효한 한 번의 Submit을 새 Business Action으로 만들어 UUID를 한 번만 생성한다.
  const submitTransfer = (values: TransferFormValues) => {
    transferMutation.mutate(createTransferAction(values))
  }

  return (
    <section className="page-stack transfer-page">
      <p className="eyebrow">TRANSFER</p><h1>계좌 이체</h1><p className="page-lead">새 이체마다 고유한 멱등성 키로 안전하게 처리합니다.</p>
      {accounts.isPending && <LoadingState title="출금 계좌를 불러오는 중입니다." />}
      {accounts.isError && <ErrorState title="계좌를 불러오지 못했습니다." description={apiErrorMessage(accounts.error, '잠시 후 다시 시도해 주세요.')} />}
      {accounts.isSuccess && activeAccounts.length === 0 && <EmptyState title="이체 가능한 계좌가 없습니다." description="ACTIVE 상태 계좌가 있어야 이체할 수 있습니다." />}
      {accounts.isSuccess && activeAccounts.length > 0 && <form className="transfer-form" onSubmit={form.handleSubmit(submitTransfer)} noValidate>
        <label htmlFor="from-account">출금 계좌</label>
        <select id="from-account" disabled={transferMutation.isPending} {...form.register('fromAccountId')}>
          <option value="">계좌를 선택해 주세요.</option>
          {activeAccounts.map((account) => <option key={account.accountId} value={String(account.accountId)}>{account.accountNumber} · {formatWon(account.balance)}</option>)}
        </select>
        {form.formState.errors.fromAccountId && <p className="field-error">{form.formState.errors.fromAccountId.message}</p>}

        <label htmlFor="to-account-number">받는 계좌번호</label>
        <input id="to-account-number" inputMode="numeric" autoComplete="off" placeholder="계좌번호 입력" disabled={transferMutation.isPending} {...form.register('toAccountNumber')} />
        {form.formState.errors.toAccountNumber && <p className="field-error">{form.formState.errors.toAccountNumber.message}</p>}

        <label htmlFor="transfer-amount">이체 금액</label>
        <div className="amount-input"><input id="transfer-amount" inputMode="numeric" placeholder="100000" disabled={transferMutation.isPending} {...form.register('amount')} /><span>원</span></div>
        {validPreview && <p className="amount-preview">{formatWon(amount)}</p>}
        {form.formState.errors.amount && <p className="field-error">{form.formState.errors.amount.message}</p>}
        {transferMutation.isError && <div className="request-error" role="alert">{apiErrorMessage(transferMutation.error, '이체하지 못했습니다.')}</div>}
        {transferMutation.isSuccess && <div className="transfer-success" role="status"><p className="eyebrow">COMPLETED</p><h2>이체 성공</h2><strong>{formatWon(transferMutation.data.amount)}</strong><dl><div><dt>이체 번호</dt><dd>{transferMutation.data.transferId}</dd></div><div><dt>상태</dt><dd>{transferMutation.data.status}</dd></div><div><dt>출금 후 잔액</dt><dd>{formatWon(transferMutation.data.fromBalanceAfter)}</dd></div></dl></div>}
        <button className="primary-button" type="submit" disabled={transferMutation.isPending}>{transferMutation.isPending ? '이체 처리 중...' : '이체하기'}</button>
      </form>}
    </section>
  )
}
