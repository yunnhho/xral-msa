import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import client from '../api/client'
import { useAuth } from '../contexts/AuthContext'
import type { ApiResponse, TokenPair } from '../types/api'

export default function SignupPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [form, setForm] = useState({ loginId: '', password: '', name: '', email: '', phone: '', birthDate: '' })
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const { data } = await client.post<ApiResponse<TokenPair>>('/api/auth/signup', form)
      if (data.data) {
        login(data.data)
        navigate('/home')
      }
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: ApiResponse<unknown> } })?.response?.data?.message
      setError(msg ?? '회원가입에 실패했습니다.')
    } finally {
      setLoading(false)
    }
  }

  const field = (label: string, key: keyof typeof form, type = 'text', placeholder = '') => (
    <>
      <label>{label}</label>
      <input
        type={type}
        value={form[key]}
        placeholder={placeholder}
        onChange={(e) => setForm({ ...form, [key]: e.target.value })}
        required
        style={styles.input}
      />
    </>
  )

  return (
    <div style={styles.center}>
      <form onSubmit={handleSubmit} style={styles.card}>
        <h2>회원가입</h2>
        {error && <p style={styles.error}>{error}</p>}
        {field('아이디 (5~20자 영문+숫자)', 'loginId', 'text', 'alice123')}
        {field('비밀번호 (8자 이상, 영문/숫자/특수문자)', 'password', 'password')}
        {field('이름', 'name')}
        {field('이메일', 'email', 'email')}
        {field('휴대폰 (01012345678)', 'phone', 'tel')}
        {field('생년월일 (YYYYMMDD)', 'birthDate', 'text', '19900101')}
        <button type="submit" disabled={loading} style={styles.btn}>
          {loading ? '처리 중...' : '가입하기'}
        </button>
        <p style={{ textAlign: 'center', marginTop: 8 }}>
          이미 계정이 있으신가요? <Link to="/login">로그인</Link>
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
}
