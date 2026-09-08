import { createContext, useContext } from 'react'
import type { LoginCredentials } from '../api/authApi'

export interface AuthContextValue {
  accessToken: string | null
  authenticated: boolean
  initialized: boolean
  login: (credentials: LoginCredentials) => Promise<void>
  logout: () => Promise<void>
  bootstrap: () => Promise<void>
}

export const AuthContext = createContext<AuthContextValue | null>(null)

// 현재 AuthProvider가 제공하는 인증 상태와 동작을 반환한다.
export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth는 AuthProvider 안에서 사용해야 합니다.')
  }
  return context
}
