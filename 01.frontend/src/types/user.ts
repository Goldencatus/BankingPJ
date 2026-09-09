export type UserRole = 'USER'

export interface CurrentUserResponse {
  userId: number
  name: string
  role: UserRole
}
