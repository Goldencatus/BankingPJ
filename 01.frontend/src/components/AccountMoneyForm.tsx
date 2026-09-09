import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useForm, useWatch } from 'react-hook-form'
import { accountDetailQueryKey, accountTransactionsQueryKey, accountsQueryKey } from '../api/accountQueryKeys'
import { apiErrorMessage } from '../api/client'
import { deposit, withdraw } from '../api/financialApi'
import { formatWon } from '../utils/accountFormat'
import { moneyFormSchema, type MoneyFormValues } from '../validation/financialSchemas'

interface AccountMoneyFormProps {
  accountId: string
  kind: 'deposit' | 'withdrawal'
  disabled: boolean
}

const copy = {
  deposit: { title: '입금', action: deposit, pending: '입금 중...', failure: '입금하지 못했습니다.' },
  withdrawal: { title: '출금', action: withdraw, pending: '출금 중...', failure: '출금하지 못했습니다.' },
}

// 입금 또는 출금 요청과 검증, 성공 후 계좌 캐시 갱신을 공통 처리한다.
export function AccountMoneyForm({ accountId, kind, disabled }: AccountMoneyFormProps) {
  const queryClient = useQueryClient()
  const form = useForm<MoneyFormValues>({ resolver: zodResolver(moneyFormSchema), defaultValues: { amount: '' } })
  const selected = copy[kind]
  const movement = useMutation({
    mutationFn: (values: MoneyFormValues) => selected.action(accountId, values.amount),
    onSuccess: async () => {
      form.reset()
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: accountsQueryKey() }),
        queryClient.invalidateQueries({ queryKey: accountDetailQueryKey(accountId) }),
        queryClient.invalidateQueries({ queryKey: accountTransactionsQueryKey(accountId) }),
      ])
    },
  })
  const amount = useWatch({ control: form.control, name: 'amount' })
  const validPreview = /^[1-9]\d*$/.test(amount) && amount.length <= 15

  return (
    <form className="money-form" onSubmit={form.handleSubmit((values) => movement.mutate(values))} noValidate>
      <h3>{selected.title}</h3>
      <label htmlFor={`${kind}-amount`}>금액</label>
      <div className="amount-input"><input id={`${kind}-amount`} inputMode="numeric" placeholder="10000" disabled={disabled || movement.isPending} {...form.register('amount')} /><span>원</span></div>
      {validPreview && <p className="amount-preview">{formatWon(amount)}</p>}
      {form.formState.errors.amount && <p className="field-error">{form.formState.errors.amount.message}</p>}
      {movement.isError && <div className="request-error" role="alert">{apiErrorMessage(movement.error, selected.failure)}</div>}
      {movement.isSuccess && <div className="success-message" role="status"><strong>{selected.title} 완료</strong><span>처리 금액 {formatWon(movement.data.amount)}</span><span>처리 후 잔액 {formatWon(movement.data.balanceAfter)}</span></div>}
      <button className="primary-button compact" type="submit" disabled={disabled || movement.isPending}>{movement.isPending ? selected.pending : `${selected.title}하기`}</button>
    </form>
  )
}
