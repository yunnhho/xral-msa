import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import client from '../api/client'
import { useAuth } from '../contexts/AuthContext'
import type { ApiResponse, TokenPair } from '../types/api'

export default function GuestLoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [tab, setTab] = useState<'login' | 'register'>('login')
  const [loginForm, setLoginForm] = useState({ accessCode: '', phone: '', password: '' })
  const [regForm, setRegForm] = useState({ name: '', phone: '', password: '' })
  const [accessCode, setAccessCode] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const { data } = await client.post<ApiResponse<TokenPair>>('/api/auth/guest/login', loginForm)
      if (data.data) {
        login(data.data)
        navigate('/home')
      }
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: ApiResponse<unknown> } })?.response?.data?.message
      setError(msg ?? '로그인 실패')
    } finally {
      setLoading(false)
    }
  }

  const handleRegister = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const { data } = await client.post<ApiResponse<{ accessCode: string; accessToken: string; refreshToken: string; role: string; userId: number }>>('/api/auth/guest/register', regForm)
      if (data.data) {
        setAccessCode(data.data.accessCode)
      }
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: ApiResponse<unknown> } })?.response?.data?.message
      setError(msg ?? '가입 실패')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={styles.center}>
      <div style={styles.card}>
        <h2>비회원 {tab === 'login' ? '로그인' : '가입'}</h2>
        <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
          <button onClick={() => setTab('login')} style={tab === 'login' ? styles.tabActive : styles.tab}>로그인</button>
          <button onClick={() => setTab('register')} style={tab === 'register' ? styles.tabActive : styles.tab}>신규 가입</button>
        </div>
        {error && <p style={styles.error}>{error}</p>}
        {accessCode && (
          <div style={{ background: '#e8f5e9', padding: 12, borderRadius: 4, marginBottom: 12 }}>
            <strong>가입 완료!</strong> 액세스 코드: <code>{accessCode}</code><br />
            반드시 저장해 두세요. 예약 조회 시 필요합니다.
          </div>
        )}
        {tab === 'login' ? (
          <form onSubmit={handleLogin} style={styles.form}>
            <label>액세스 코드</label>
            <input value={loginForm.accessCode} onChange={e => setLoginForm({ ...loginForm, accessCode: e.target.value })} required style={styles.input} />
            <label>휴대폰</label>
            <input value={loginForm.phone} onChange={e => setLoginForm({ ...loginForm, phone: e.target.value })} required style={styles.input} />
            <label>비밀번호 (4자리)</label>
            <input type="password" value={loginForm.password} onChange={e => setLoginForm({ ...loginForm, password: e.target.value })} required style={styles.input} />
            <button type="submit" disabled={loading} style={styles.btn}>{loading ? '처리 중...' : '로그인'}</button>
          </form>
        ) : (
          <form onSubmit={handleRegister} style={styles.form}>
            <label>이름</label>
            <input value={regForm.name} onChange={e => setRegForm({ ...regForm, name: e.target.value })} required style={styles.input} />
            <label>휴대폰</label>
            <input value={regForm.phone} onChange={e => setRegForm({ ...regForm, phone: e.target.value })} required style={styles.input} />
            <label>비밀번호 (4~6자리 숫자)</label>
            <input type="password" value={regForm.password} onChange={e => setRegForm({ ...regForm, password: e.target.value })} required style={styles.input} />
            <button type="submit" disabled={loading} style={styles.btn}>{loading ? '처리 중...' : '가입하기'}</button>
          </form>
        )}
        <p style={{ textAlign: 'center', marginTop: 12 }}>
          <Link to="/login">회원 로그인으로</Link>
        </p>
      </div>
    </div>
  )
}

const styles = {
  center: { display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', background: '#f5f5f5' } as React.CSSProperties,
  card: { background: '#fff', padding: 32, borderRadius: 8, boxShadow: '0 2px 8px rgba(0,0,0,.12)', width: 360 },
  form: { display: 'flex', flexDirection: 'column' as const, gap: 8 },
  input: { padding: '8px 10px', borderRadius: 4, border: '1px solid #ccc', fontSize: 14 } as React.CSSProperties,
  btn: { padding: '10px', background: '#1a73e8', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 15 } as React.CSSProperties,
  error: { color: '#d32f2f', fontSize: 13 } as React.CSSProperties,
  tab: { flex: 1, padding: '6px', border: '1px solid #ccc', borderRadius: 4, cursor: 'pointer', background: '#fff' } as React.CSSProperties,
  tabActive: { flex: 1, padding: '6px', border: '1px solid #1a73e8', borderRadius: 4, cursor: 'pointer', background: '#1a73e8', color: '#fff' } as React.CSSProperties,
}
