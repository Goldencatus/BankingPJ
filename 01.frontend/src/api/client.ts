import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { getAccessToken, setAccessToken } from '../auth/tokenStore'
import type { ApiResponse, LoginResponse } from '../types/api'

const baseURL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'

export const apiClient = axios.create({ baseURL, withCredentials: true })
const refreshClient = axios.create({ baseURL, withCredentials: true })

interface RetryableRequest extends InternalAxiosRequestConfig {
  _authRetry?: boolean
}

let refreshPromise: Promise<string> | null = null

// HttpOnly Refresh Cookie로 Access Token을 한 번만 재발급하고 메모리에 저장한다.
export function refreshAccessToken() {
  if (!refreshPromise) {
    refreshPromise = refreshClient
      .post<ApiResponse<LoginResponse>>('/api/auth/refresh')
      .then(({ data }) => {
        if (!data.success || !data.data?.accessToken) {
          throw new Error('인증을 복구할 수 없습니다.')
        }
        setAccessToken(data.data.accessToken)
        return data.data.accessToken
      })
      .catch((error) => {
        setAccessToken(null)
        throw error
      })
      .finally(() => {
        refreshPromise = null
      })
  }
  return refreshPromise
}

// 인증된 API 요청에 현재 메모리 Access Token을 Bearer Header로 추가한다.
apiClient.interceptors.request.use((config) => {
  const token = getAccessToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// AUTH_003 응답에서 refresh를 단 한 번 공유 실행하고 원래 요청을 한 번 재시도한다.
apiClient.interceptors.response.use(undefined, async (error: AxiosError<ApiResponse<unknown>>) => {
  const request = error.config as RetryableRequest | undefined
  const isExpiredAccessToken = error.response?.status === 401 && error.response.data?.error?.code === 'AUTH_003'
  const isRefreshRequest = request?.url?.includes('/api/auth/refresh')
  if (!request || request._authRetry || isRefreshRequest || !isExpiredAccessToken) {
    return Promise.reject(error)
  }

  request._authRetry = true
  const token = await refreshAccessToken()
  request.headers.Authorization = `Bearer ${token}`
  return apiClient(request)
})

// Backend 공통 오류 응답에서 사용자에게 표시할 안전한 메시지를 선택한다.
export function apiErrorMessage(error: unknown, fallback: string) {
  if (axios.isAxiosError<ApiResponse<unknown>>(error)) {
    return error.response?.data?.error?.message || fallback
  }
  return fallback
}
