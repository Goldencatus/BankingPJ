type TokenListener = (token: string | null) => void

let accessToken: string | null = null
const listeners = new Set<TokenListener>()

// 현재 브라우저 메모리에만 보관된 Access Token을 반환한다.
export function getAccessToken() {
  return accessToken
}

// Access Token을 메모리에 갱신하고 인증 상태 구독자에게 알린다.
export function setAccessToken(token: string | null) {
  accessToken = token
  listeners.forEach((listener) => listener(token))
}

// AuthProvider가 메모리 토큰 변경을 반영할 수 있도록 구독을 등록한다.
export function subscribeAccessToken(listener: TokenListener) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}
