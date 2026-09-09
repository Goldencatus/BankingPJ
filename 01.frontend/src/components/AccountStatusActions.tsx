import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { activateAccount, closeAccount, suspendAccount } from '../api/accountApi'
import { accountDetailQueryKey, accountsQueryKey } from '../api/accountQueryKeys'
import { apiErrorMessage } from '../api/client'
import type { AccountStatus } from '../types/account'

interface AccountStatusActionsProps {
  accountId: string
  status: AccountStatus
}

type StatusCommand = 'suspend' | 'activate' | 'close'

const commands = {
  suspend: { title: '계좌 일시정지', message: '계좌를 일시정지하시겠습니까?', action: suspendAccount },
  activate: { title: '다시 사용', message: '계좌를 다시 사용하시겠습니까?', action: activateAccount },
  close: { title: '계좌 해지', message: '계좌를 해지하시겠습니까? 해지된 계좌는 다시 활성화할 수 없습니다.', action: closeAccount },
}

// 현재 계좌 상태에 허용된 명령과 확인 Dialog, Mutation 결과를 관리한다.
export function AccountStatusActions({ accountId, status }: AccountStatusActionsProps) {
  const queryClient = useQueryClient()
  const [confirmation, setConfirmation] = useState<StatusCommand | null>(null)
  const mutation = useMutation({
    mutationFn: (command: StatusCommand) => commands[command].action(accountId),
    onSuccess: async () => {
      setConfirmation(null)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: accountsQueryKey() }),
        queryClient.invalidateQueries({ queryKey: accountDetailQueryKey(accountId) }),
      ])
    },
  })

  // 확인한 상태 명령을 한 번만 Mutation에 전달한다.
  const confirmChange = () => {
    if (confirmation) mutation.mutate(confirmation)
  }

  if (status === 'CLOSED') return <div className="restriction-message" role="note">해지된 계좌입니다. 상세 정보와 과거 거래내역만 조회할 수 있습니다.</div>

  return (
    <section className="status-actions" aria-label="계좌 상태 관리">
      <h2>계좌 상태 관리</h2>
      <div className="status-action-buttons">
        {status === 'ACTIVE' && <button className="secondary-button" type="button" disabled={mutation.isPending} onClick={() => setConfirmation('suspend')}>계좌 일시정지</button>}
        {status === 'SUSPENDED' && <button className="secondary-button" type="button" disabled={mutation.isPending} onClick={() => setConfirmation('activate')}>다시 사용</button>}
        <button className="danger-button" type="button" disabled={mutation.isPending} onClick={() => setConfirmation('close')}>계좌 해지</button>
      </div>
      {mutation.isError && <div className="request-error" role="alert">{apiErrorMessage(mutation.error, '계좌 상태를 변경하지 못했습니다.')}</div>}
      {mutation.isSuccess && <div className="success-message" role="status">계좌 상태가 변경되었습니다.</div>}
      {confirmation && <div className="dialog-backdrop"><div className="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="confirm-title"><h3 id="confirm-title">{commands[confirmation].title}</h3><p>{commands[confirmation].message}</p><div><button className="secondary-button" type="button" disabled={mutation.isPending} onClick={() => setConfirmation(null)}>취소</button><button className={confirmation === 'close' ? 'danger-button' : 'primary-button compact'} type="button" disabled={mutation.isPending} onClick={confirmChange}>{mutation.isPending ? '처리 중...' : '확인'}</button></div></div></div>}
    </section>
  )
}
