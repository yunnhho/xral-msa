import { useEffect } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import { setRefreshToken } from '../api/client'

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
    // We don't have user info from query params — store tokens and redirect.
    // The AuthProvider will reissue and populate user on next mount.
    setRefreshToken(refreshToken)
    // Construct a minimal pair to trigger login state (reissue will correct it)
    login({
      accessToken,
      refreshToken,
      tokenType: 'Bearer',
      accessExpiresIn: 1800,
      refreshExpiresIn: 1209600,
      user: { userId: 0, name: '', role: 'ROLE_MEMBER' },
    })
    navigate('/home')
  }, [params, login, navigate])

  return <div style={{ textAlign: 'center', marginTop: 100 }}>OAuth 처리 중...</div>
}
