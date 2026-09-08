import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'
import { setAccessToken } from '../auth/tokenStore'

// 테스트마다 메모리 토큰과 Mock 상태를 격리한다.
afterEach(() => {
  cleanup()
  setAccessToken(null)
  localStorage.clear()
  sessionStorage.clear()
})
