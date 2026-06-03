import { useEffect } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import { setAccessToken, setRefreshToken, clearAccessToken, clearRefreshToken } from '../api/client'
import client from '../api/client'
import type { ApiResponse, MeResponse } from '../types/api'
import { C } from '../styles/theme'

export default function OAuthCallbackPage() {
  const [params] = useSearchParams()
  const { login } = useAuth()
  const navigate = useNavigate()

  useEffect(() => {
    const accessToken = params.get('accessToken')
    const refreshToken = params.get('refreshToken')
    if (!accessToken || !refreshToken) { navigate('/login'); return }
    setAccessToken(accessToken)
    setRefreshToken(refreshToken)
    client.get<ApiResponse<MeResponse>>('/api/auth/me')
      .then(({ data }) => {
        if (data.data) {
          login({ userId: data.data.userId, name: data.data.name, role: data.data.role, accessToken, refreshToken })
          navigate('/home')
        } else {
          clearAccessToken()
          clearRefreshToken()
          navigate('/login', { state: { oauthFailed: true } })
        }
      })
      .catch(() => {
        clearAccessToken()
        clearRefreshToken()
        navigate('/login', { state: { oauthFailed: true } })
      })
  }, [params, login, navigate])

  return (
    <div style={{ minHeight: '100vh', background: C.bg, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <div style={{ textAlign: 'center' }}>
        <div style={{ width: 48, height: 48, border: `4px solid ${C.accentLight}`, borderTop: `4px solid ${C.accent}`, borderRadius: '50%', margin: '0 auto 20px', animation: 'spin 0.8s linear infinite' }} />
        <p style={{ fontSize: 15, color: C.textSub, fontWeight: 500 }}>로그인 처리 중...</p>
        <p style={{ fontSize: 12, color: C.textMuted }}>잠시만 기다려 주세요.</p>
      </div>
    </div>
  )
}
