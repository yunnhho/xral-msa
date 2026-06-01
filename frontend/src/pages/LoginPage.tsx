import { useState } from 'react'
import { Link, useNavigate, useLocation } from 'react-router-dom'
import client from '../api/client'
import { useAuth } from '../contexts/AuthContext'
import type { ApiResponse, TokenPair } from '../types/api'

export default function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const signupSuccess = (location.state as { signupSuccess?: boolean } | null)?.signupSuccess
  const [form, setForm] = useState({ email: '', password: '' })
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const { data } = await client.post<ApiResponse<TokenPair>>('/api/auth/login', form)
      if (data.data) {
        login(data.data)
        navigate('/home')
      }
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: ApiResponse<unknown> } })?.response?.data?.message
      setError(msg ?? '로그인에 실패했습니다.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={styles.center}>
      <form onSubmit={handleSubmit} style={styles.card}>
        <h2>XRail 로그인</h2>
        {signupSuccess && <p style={styles.success}>회원가입이 완료되었습니다. 로그인해주세요.</p>}
        {error && <p style={styles.error}>{error}</p>}
        <label>이메일</label>
        <input
          type="email"
          value={form.email}
          onChange={(e) => setForm({ ...form, email: e.target.value })}
          required
          style={styles.input}
        />
        <label>비밀번호</label>
        <input
          type="password"
          value={form.password}
          onChange={(e) => setForm({ ...form, password: e.target.value })}
          required
          style={styles.input}
        />
        <button type="submit" disabled={loading} style={styles.btn}>
          {loading ? '로그인 중...' : '로그인'}
        </button>
        <div style={{ marginTop: 12, textAlign: 'center' }}>
          <a href="/oauth2/authorization/kakao" style={styles.oauthBtn('#FEE500')}>
            카카오 로그인
          </a>
          <a href="/oauth2/authorization/naver" style={styles.oauthBtn('#03C75A')}>
            네이버 로그인
          </a>
        </div>
        <p style={{ textAlign: 'center', marginTop: 16 }}>
          계정이 없으신가요? <Link to="/signup">회원가입</Link>
        </p>
        <p style={{ textAlign: 'center' }}>
          비회원 조회 <Link to="/guest/login">비회원 로그인</Link>
        </p>
      </form>
    </div>
  )
}

const styles = {
  center: { display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', background: '#f5f5f5' } as React.CSSProperties,
  card: { background: '#fff', padding: 32, borderRadius: 8, boxShadow: '0 2px 8px rgba(0,0,0,.12)', width: 360, display: 'flex', flexDirection: 'column' as const, gap: 8 },
  input: { padding: '8px 10px', borderRadius: 4, border: '1px solid #ccc', fontSize: 14 } as React.CSSProperties,
  btn: { padding: '10px', background: '#1a73e8', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 15 } as React.CSSProperties,
  error: { color: '#d32f2f', fontSize: 13 } as React.CSSProperties,
  success: { color: '#2e7d32', fontSize: 13, background: '#e8f5e9', padding: '6px 10px', borderRadius: 4 } as React.CSSProperties,
  oauthBtn: (bg: string) => ({ display: 'block', margin: '6px 0', padding: '8px', background: bg, borderRadius: 4, textAlign: 'center' as const, textDecoration: 'none', color: '#000', fontSize: 14 }),
}
