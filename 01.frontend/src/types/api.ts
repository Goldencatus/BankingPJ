export interface ApiError {
  code: string
  message: string
}

export interface ApiResponse<T> {
  success: boolean
  data: T | null
  error: ApiError | null
}

export interface LoginResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
}
