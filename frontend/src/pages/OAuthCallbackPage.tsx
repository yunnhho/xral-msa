import { useEffect } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import { setAccessToken, setRefreshToken } from '../api/client'
import client from '../api/client'
import type { ApiResponse, MeResponse } from '../types/api'

export default function OAuthCallbackPage() {
  const [params] = useSearchParams()
  const { login } = useAuth()
  const navigate = useNavigate()

  useEffect(() => {
    const accessToken = params.get('accessToken')
    const refreshToken = params.get('refreshToken')
    if (!accessToken || !refreshToken) {
      navigate('/login')
      return
    }
    setAccessToken(accessToken)
    setRefreshToken(refreshToken)
    client.get<ApiResponse<MeResponse>>('/api/auth/me')
      .then(({ data }) => {
        if (data.data) {
          login({ userId: data.data.userId, name: data.data.name, role: data.data.role, accessToken, refreshToken })
        }
      })
      .catch(() => {})
      .finally(() => navigate('/home'))
  }, [params, login, navigate])

  return <div style={{ textAlign: 'center', marginTop: 100 }}>OAuth 처리 중...</div>
}
