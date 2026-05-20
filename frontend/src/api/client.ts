import axios, { AxiosError, InternalAxiosRequestConfig } from 'axios'
import type { ApiResponse, TokenPair } from '../types/api'

const REFRESH_TOKEN_KEY = 'xrail_refresh_token'

// Access token stored in memory only (F2)
let memoryAccessToken: string | null = null

export function setAccessToken(token: string) {
  memoryAccessToken = token
}

export function clearAccessToken() {
  memoryAccessToken = null
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_TOKEN_KEY)
}

export function setRefreshToken(token: string) {
  localStorage.setItem(REFRESH_TOKEN_KEY, token)
}

export function clearRefreshToken() {
  localStorage.removeItem(REFRESH_TOKEN_KEY)
}

const client = axios.create({
  baseURL: import.meta.env.VITE_API_BASE ?? '',
  timeout: 10_000,
})

// Inject Authorization header (F1)
client.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  if (memoryAccessToken) {
    config.headers.Authorization = `Bearer ${memoryAccessToken}`
  }
  return config
})

let isRefreshing = false
let refreshQueue: Array<(token: string) => void> = []

// 401 → refresh → retry (F1)
client.interceptors.response.use(
  (res) => res,
  async (error: AxiosError<ApiResponse<unknown>>) => {
    const original = error.config as InternalAxiosRequestConfig & { _retry?: boolean }
    if (error.response?.status === 401 && !original._retry) {
      original._retry = true

      const refreshToken = getRefreshToken()
      if (!refreshToken) {
        triggerLogout()
        return Promise.reject(error)
      }

      if (isRefreshing) {
        return new Promise((resolve) => {
          refreshQueue.push((token) => {
            original.headers.Authorization = `Bearer ${token}`
            resolve(client(original))
          })
        })
      }

      isRefreshing = true
      try {
        const { data } = await axios.post<ApiResponse<TokenPair>>(
          '/api/auth/reissue',
          { refreshToken },
        )
        const newAccess = data.data!.accessToken
        const newRefresh = data.data!.refreshToken
        setAccessToken(newAccess)
        setRefreshToken(newRefresh)
        refreshQueue.forEach((cb) => cb(newAccess))
        refreshQueue = []
        original.headers.Authorization = `Bearer ${newAccess}`
        return client(original)
      } catch {
        triggerLogout()
        return Promise.reject(error)
      } finally {
        isRefreshing = false
      }
    }
    return Promise.reject(error)
  },
)

function triggerLogout() {
  clearAccessToken()
  clearRefreshToken()
  window.dispatchEvent(new Event('xrail:logout'))
}

export default client
