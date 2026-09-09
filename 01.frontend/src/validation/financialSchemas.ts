import { z } from 'zod'

export const wonAmountSchema = z.string()
  .min(1, '금액을 입력해 주세요.')
  .regex(/^[1-9]\d*$/, '금액은 0보다 큰 원 단위 정수여야 합니다.')
  .max(15, '금액은 15자리 이하여야 합니다.')

export const moneyFormSchema = z.object({ amount: wonAmountSchema })

export const transferFormSchema = z.object({
  fromAccountId: z.string().min(1, '출금 계좌를 선택해 주세요.'),
  toAccountNumber: z.string().trim().min(1, '받는 계좌번호를 입력해 주세요.').max(30, '계좌번호는 30자 이하여야 합니다.'),
  amount: wonAmountSchema,
})

export type MoneyFormValues = z.infer<typeof moneyFormSchema>
export type TransferFormValues = z.infer<typeof transferFormSchema>
