import { useCallback, useEffect, useMemo, useState, type PropsWithChildren } from 'react'
import { loginRequest, logoutRequest, type LoginCredentials } from '../api/authApi'
import { refreshAccessToken } from '../api/client'
import { getAccessToken, setAccessToken, subscribeAccessToken } from './tokenStore'
import { AuthContext } from './AuthContext'

// 메모리 Access Token과 로그인·복구·로그아웃 상태를 애플리케이션에 제공한다.
export function AuthProvider({ children }: PropsWithChildren) {
  const [accessToken, updateAccessToken] = useState(getAccessToken())
  const [initialized, setInitialized] = useState(false)

  // 앱 시작 시 Refresh Cookie로 로그인 상태를 복원한다.
  const bootstrap = useCallback(async () => {
    try {
      await refreshAccessToken()
    } catch {
      setAccessToken(null)
    } finally {
      setInitialized(true)
    }
  }, [])

  // 로그인 API에서 받은 Access Token을 메모리에 저장한다.
  const login = useCallback(async (credentials: LoginCredentials) => {
    const response = await loginRequest(credentials)
    setAccessToken(response.accessToken)
    setInitialized(true)
  }, [])

  // 서버 Refresh Cookie와 메모리 Access Token을 함께 정리한다.
  const logout = useCallback(async () => {
    try {
      await logoutRequest()
    } finally {
      setAccessToken(null)
      setInitialized(true)
    }
  }, [])

  useEffect(() => subscribeAccessToken(updateAccessToken), [])
  useEffect(() => {
    void bootstrap()
  }, [bootstrap])

  const value = useMemo(() => ({
    accessToken,
    authenticated: Boolean(accessToken),
    initialized,
    login,
    logout,
    bootstrap,
  }), [accessToken, initialized, login, logout, bootstrap])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
