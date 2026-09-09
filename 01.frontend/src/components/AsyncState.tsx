import type { ReactNode } from 'react'

interface StateProps {
  title: string
  description?: string
  children?: ReactNode
}

// 비동기 요청이 진행 중임을 일관된 상태 카드로 알린다.
export function LoadingState({ title, description }: StateProps) {
  return <div className="state-card" role="status" aria-live="polite"><strong>{title}</strong>{description && <p>{description}</p>}</div>
}

// 안전한 오류 메시지와 선택적인 복구 동작을 일관되게 표시한다.
export function ErrorState({ title, description, children }: StateProps) {
  return <div className="state-card state-error" role="alert"><strong>{title}</strong>{description && <p>{description}</p>}{children}</div>
}

// 조회 결과가 비어 있는 정상 상태와 다음 행동을 일관되게 표시한다.
export function EmptyState({ title, description, children }: StateProps) {
  return <div className="state-card"><strong>{title}</strong>{description && <p>{description}</p>}{children}</div>
}
