import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '../api/client'
import { createTransferAction, transfer } from '../api/financialApi'
import { formatWon } from '../utils/accountFormat'

afterEach(() => vi.restoreAllMocks())

describe('금융 공통 정책', () => {
  it.each([
    ['100000.0000', '100,000원'],
    ['0.0000', '0원'],
    ['1.5000', '1.5원'],
  ])('%s 금액을 %s으로 표시한다', (amount, expected) => {
    expect(formatWon(amount)).toBe(expected)
  })

  it('이체 API에 Business Action의 Idempotency-Key Header를 포함한다', async () => {
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { success: true, data: { transferId: 1 }, error: null } })
    const action = { request: { fromAccountId: '7', toAccountNumber: '99998888777766', amount: '30000' }, idempotencyKey: 'fixed-uuid' }
    await transfer(action)
    expect(post).toHaveBeenCalledWith('/api/transfers', { fromAccountId: 7, toAccountNumber: '99998888777766', amount: 30000 }, { headers: { 'Idempotency-Key': 'fixed-uuid' } })
  })

  it('같은 Business Action의 기술적 재시도에서는 동일 Key를 재사용한다', async () => {
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { success: true, data: { transferId: 1 }, error: null } })
    const action = { request: { fromAccountId: '7', toAccountNumber: '99998888777766', amount: '30000' }, idempotencyKey: 'retry-uuid' }
    await transfer(action)
    await transfer(action)
    expect(post.mock.calls.map((call) => call[2]?.headers?.['Idempotency-Key'])).toEqual(['retry-uuid', 'retry-uuid'])
  })

  it('새로운 이체 Action마다 새로운 UUID를 생성한다', () => {
    const request = { fromAccountId: '7', toAccountNumber: '99998888777766', amount: '30000' }
    const first = createTransferAction(request)
    const second = createTransferAction(request)
    expect(first.idempotencyKey).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i)
    expect(second.idempotencyKey).not.toBe(first.idempotencyKey)
  })
})
