import type { AccountStatus, MoneyValue } from '../types/account'
import type { LedgerEntryType } from '../types/transaction'

const statusLabels: Record<AccountStatus, string> = {
  ACTIVE: '정상',
  SUSPENDED: '일시정지',
  CLOSED: '해지',
}

// 금융 금액을 계산하지 않고 불필요한 0만 제거해 천 단위 원화로 표시한다.
export function formatWon(value: MoneyValue) {
  const raw = String(value)
  const [integerPart, decimalPart = ''] = raw.split('.')
  const groupedInteger = integerPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  const meaningfulDecimal = decimalPart.replace(/0+$/, '')
  return `${groupedInteger}${meaningfulDecimal ? `.${meaningfulDecimal}` : ''}원`
}

// Backend 상태 코드와 사용자가 이해할 수 있는 상태명을 함께 반환한다.
export function accountStatusLabel(status: AccountStatus) {
  return `${status} · ${statusLabels[status]}`
}

// Backend LocalDateTime을 한국어 날짜와 시간으로 표시한다.
export function formatCreatedAt(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('ko-KR')
}

// 원장 유형에 따라 절댓값 금액 앞에 입출금 부호를 붙인다.
export function formatSignedWon(value: MoneyValue, type: LedgerEntryType) {
  const absolute = String(value).replace(/^-/, '')
  return `${type === 'CREDIT' ? '+' : '-'}${formatWon(absolute)}`
}

// transferId와 원장 유형을 조합해 거래의 실제 의미를 한국어로 구분한다.
export function transactionLabel(type: LedgerEntryType, transferId: number | null) {
  if (transferId !== null) return type === 'CREDIT' ? '이체 입금' : '이체 출금'
  return type === 'CREDIT' ? '입금' : '출금'
}
