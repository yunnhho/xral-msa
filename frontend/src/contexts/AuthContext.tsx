import React, { createContext, useCallback, useContext, useEffect, useState } from 'react'
import {
  clearAccessToken,
  clearRefreshToken,
  getRefreshToken,
  setAccessToken,
  setRefreshToken,
} from '../api/client'
import client from '../api/client'
import type { ApiResponse, TokenPair } from '../types/api'

interface AuthUser {
  userId: number
  name: string
  role: string
}

interface AuthContextValue {
  user: AuthUser | null
  isLoading: boolean
  login: (pair: TokenPair) => void
  logout: () => Promise<void>
  isAuthenticated: boolean
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null)
  const [isLoading, setIsLoading] = useState(true)

  const login = useCallback((pair: TokenPair) => {
    setAccessToken(pair.accessToken)
    setRefreshToken(pair.refreshToken)
    setUser(pair.user)
  }, [])

  const logout = useCallback(async () => {
    try {
      await client.post('/api/auth/logout')
    } catch {
      // best-effort
    }
    clearAccessToken()
    clearRefreshToken()
    setUser(null)
  }, [])

  // Restore session from refresh token on mount
  useEffect(() => {
    const rt = getRefreshToken()
    if (!rt) {
      setIsLoading(false)
      return
    }
    client
      .post<ApiResponse<TokenPair>>('/api/auth/reissue', { refreshToken: rt })
      .then(({ data }) => {
        if (data.data) login(data.data)
      })
      .catch(() => {
        clearRefreshToken()
      })
      .finally(() => setIsLoading(false))
  }, [login])

  // Listen for forced logout (401 after refresh failure)
  useEffect(() => {
    const handler = () => setUser(null)
    window.addEventListener('xrail:logout', handler)
    return () => window.removeEventListener('xrail:logout', handler)
  }, [])

  return (
    <AuthContext.Provider value={{ user, isLoading, login, logout, isAuthenticated: !!user }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider')
  return ctx
}
