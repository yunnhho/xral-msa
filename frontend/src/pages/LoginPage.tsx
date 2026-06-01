import { useState } from 'react'
import { Link, useNavigate, useLocation } from 'react-router-dom'
import client from '../api/client'
import { useAuth } from '../contexts/AuthContext'
import type { ApiResponse, TokenPair } from '../types/api'
import { C } from '../styles/theme'

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
      if (data.data) { login(data.data); navigate('/home') }
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: ApiResponse<unknown> } })?.response?.data?.message
      setError(msg ?? '로그인에 실패했습니다.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={{ minHeight: '100vh', background: C.bg, display: 'flex', flexDirection: 'column' }}>
      {/* Top bar */}
      <div style={{ background: C.accent, padding: '14px 24px', display: 'flex', alignItems: 'center', gap: 10 }}>
        <span style={{ color: '#fff', fontSize: 22, fontWeight: 800, letterSpacing: -0.5 }}>XRail</span>
        <span style={{ color: 'rgba(255,255,255,.55)', fontSize: 13 }}>기차 예매 시스템</span>
      </div>

      <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24 }}>
        <div style={{ width: '100%', maxWidth: 420 }}>
          {signupSuccess && (
            <div style={{ background: C.successBg, border: `1px solid #86efac`, color: C.success, borderRadius: 8, padding: '12px 16px', marginBottom: 20, fontSize: 14, display: 'flex', alignItems: 'center', gap: 8 }}>
              ✓ 회원가입이 완료되었습니다. 로그인해주세요.
            </div>
          )}

          <div style={{ background: C.surface, borderRadius: 12, boxShadow: C.shadowMd, overflow: 'hidden' }}>
            <div style={{ background: C.accent, padding: '28px 32px' }}>
              <h1 style={{ color: '#fff', margin: 0, fontSize: 22, fontWeight: 700 }}>로그인</h1>
              <p style={{ color: 'rgba(255,255,255,.7)', margin: '6px 0 0', fontSize: 13 }}>XRail 회원 서비스를 이용하세요</p>
            </div>

            <form onSubmit={handleSubmit} style={{ padding: '28px 32px', display: 'flex', flexDirection: 'column', gap: 16 }}>
              {error && (
                <div style={{ background: C.dangerBg, border: `1px solid #fca5a5`, color: C.danger, borderRadius: 6, padding: '10px 14px', fontSize: 13 }}>
                  {error}
                </div>
              )}
              <div>
                <label style={{ display: 'block', fontSize: 13, fontWeight: 600, color: C.text, marginBottom: 6 }}>이메일</label>
                <input
                  type="email" value={form.email} required placeholder="example@email.com"
                  onChange={e => setForm({ ...form, email: e.target.value })}
                  style={{ width: '100%', padding: '10px 12px', border: `1.5px solid ${C.border}`, borderRadius: 6, fontSize: 14, boxSizing: 'border-box', outline: 'none', color: C.text }}
                />
              </div>
              <div>
                <label style={{ display: 'block', fontSize: 13, fontWeight: 600, color: C.text, marginBottom: 6 }}>비밀번호</label>
                <input
                  type="password" value={form.password} required placeholder="비밀번호 입력"
                  onChange={e => setForm({ ...form, password: e.target.value })}
                  style={{ width: '100%', padding: '10px 12px', border: `1.5px solid ${C.border}`, borderRadius: 6, fontSize: 14, boxSizing: 'border-box', outline: 'none', color: C.text }}
                />
              </div>
              <button type="submit" disabled={loading}
                style={{ padding: '12px', background: loading ? C.textMuted : C.accent, color: '#fff', border: 'none', borderRadius: 6, cursor: loading ? 'not-allowed' : 'pointer', fontSize: 15, fontWeight: 700, marginTop: 4 }}>
                {loading ? '로그인 중...' : '로그인'}
              </button>

              <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginTop: 4 }}>
                <a href="http://localhost:8080/oauth2/authorization/kakao"
                  style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8, padding: '11px', background: '#FEE500', borderRadius: 6, textDecoration: 'none', color: '#3A1D1D', fontSize: 14, fontWeight: 600 }}>
                  <span style={{ fontSize: 18 }}>💬</span> 카카오로 로그인
                </a>
                <a href="http://localhost:8080/oauth2/authorization/naver"
                  style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8, padding: '11px', background: '#03C75A', borderRadius: 6, textDecoration: 'none', color: '#fff', fontSize: 14, fontWeight: 600 }}>
                  <span style={{ fontWeight: 800, fontSize: 16 }}>N</span> 네이버로 로그인
                </a>
              </div>
            </form>

            <div style={{ borderTop: `1px solid ${C.border}`, padding: '16px 32px', display: 'flex', justifyContent: 'center', gap: 20, fontSize: 13, color: C.textSub }}>
              <Link to="/signup" style={{ color: C.accent, textDecoration: 'none', fontWeight: 600 }}>회원가입</Link>
              <span style={{ color: C.border }}>|</span>
              <Link to="/guest/login" style={{ color: C.textSub, textDecoration: 'none' }}>비회원 예매 조회</Link>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
