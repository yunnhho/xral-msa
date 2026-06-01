import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import client from '../api/client'
import { useAuth } from '../contexts/AuthContext'
import type { ApiResponse, TokenPair } from '../types/api'
import { C } from '../styles/theme'

export default function GuestLoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [tab, setTab] = useState<'login' | 'register'>('login')
  const [loginForm, setLoginForm] = useState({ accessCode: '', phone: '', password: '' })
  const [regForm, setRegForm] = useState({ name: '', phone: '', password: '' })
  const [accessCode, setAccessCode] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const inputStyle: React.CSSProperties = {
    width: '100%', padding: '10px 12px', border: `1.5px solid ${C.border}`,
    borderRadius: 6, fontSize: 14, color: C.text, background: '#fff',
    boxSizing: 'border-box', outline: 'none',
  }

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault(); setError(''); setLoading(true)
    try {
      const { data } = await client.post<ApiResponse<TokenPair>>('/api/auth/non-member/login', loginForm)
      if (data.data) { login(data.data); navigate('/home') }
    } catch (err: unknown) {
      setError((err as { response?: { data?: ApiResponse<unknown> } })?.response?.data?.message ?? '로그인 실패')
    } finally { setLoading(false) }
  }

  const handleRegister = async (e: React.FormEvent) => {
    e.preventDefault(); setError(''); setLoading(true)
    try {
      const { data } = await client.post<ApiResponse<{ accessCode: string }>>('/api/auth/non-member/register', regForm)
      if (data.data) { setAccessCode(data.data.accessCode); setTab('login'); setError('') }
    } catch (err: unknown) {
      setError((err as { response?: { data?: ApiResponse<unknown> } })?.response?.data?.message ?? '가입 실패')
    } finally { setLoading(false) }
  }

  return (
    <div style={{ minHeight: '100vh', background: C.bg, display: 'flex', flexDirection: 'column' }}>
      <div style={{ background: C.accent, padding: '14px 24px', display: 'flex', alignItems: 'center', gap: 8 }}>
        <Link to="/login" style={{ color: 'rgba(255,255,255,.7)', fontSize: 13, textDecoration: 'none' }}>← 로그인</Link>
        <span style={{ color: 'rgba(255,255,255,.3)', margin: '0 4px' }}>|</span>
        <span style={{ color: '#fff', fontSize: 15, fontWeight: 700 }}>XRail</span>
        <span style={{ color: 'rgba(255,255,255,.5)', fontSize: 12 }}>비회원 서비스</span>
      </div>

      <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '32px 20px' }}>
        <div style={{ width: '100%', maxWidth: 420 }}>
          {/* 탭 */}
          <div style={{ display: 'flex', borderRadius: '10px 10px 0 0', overflow: 'hidden', border: `1px solid ${C.border}`, borderBottom: 'none' }}>
            {(['login', 'register'] as const).map(t => (
              <button key={t} onClick={() => { setTab(t); setError('') }}
                style={{ flex: 1, padding: '14px', border: 'none', cursor: 'pointer', fontSize: 14, fontWeight: 700, background: tab === t ? '#fff' : C.bg, color: tab === t ? C.accent : C.textSub, borderBottom: tab === t ? '2px solid #fff' : `2px solid ${C.border}` }}>
                {t === 'login' ? '예매 조회' : '비회원 가입'}
              </button>
            ))}
          </div>

          <div style={{ background: '#fff', borderRadius: '0 0 12px 12px', boxShadow: '0 4px 24px rgba(0,30,87,.1)', border: `1px solid ${C.border}`, borderTop: 'none' }}>
            {/* 액세스 코드 발급 완료 배너 */}
            {accessCode && (
              <div style={{ margin: '0', padding: '16px 24px', background: '#f0fdf4', borderBottom: `1px solid #bbf7d0` }}>
                <p style={{ margin: 0, fontSize: 13, fontWeight: 700, color: C.success }}>✓ 비회원 가입 완료!</p>
                <p style={{ margin: '8px 0 4px', fontSize: 12, color: C.textSub }}>아래 액세스 코드를 반드시 저장해두세요. 예매 조회 시 필요합니다.</p>
                <div style={{ background: '#fff', border: `1.5px solid #86efac`, borderRadius: 6, padding: '10px 14px', fontFamily: 'monospace', fontSize: 18, fontWeight: 800, letterSpacing: 2, color: C.text, textAlign: 'center' }}>
                  {accessCode}
                </div>
              </div>
            )}

            <div style={{ padding: '28px 28px 24px' }}>
              <p style={{ margin: '0 0 20px', fontSize: 13, color: C.textSub, lineHeight: 1.6 }}>
                {tab === 'login'
                  ? '비회원으로 예매하신 내역을 조회하거나, 추가 결제를 진행할 수 있습니다.'
                  : '이름, 휴대폰 번호, 비밀번호를 입력하고 비회원으로 예매를 시작하세요.'}
              </p>

              {error && (
                <div style={{ background: C.dangerBg, color: C.danger, borderRadius: 6, padding: '10px 14px', fontSize: 13, marginBottom: 16 }}>
                  {error}
                </div>
              )}

              {tab === 'login' ? (
                <form onSubmit={handleLogin} style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
                  <div>
                    <label style={{ display: 'block', fontSize: 12, fontWeight: 700, color: C.textSub, marginBottom: 5 }}>액세스 코드</label>
                    <input value={loginForm.accessCode} onChange={e => setLoginForm({ ...loginForm, accessCode: e.target.value })} required placeholder="가입 시 발급받은 코드" style={inputStyle} />
                  </div>
                  <div>
                    <label style={{ display: 'block', fontSize: 12, fontWeight: 700, color: C.textSub, marginBottom: 5 }}>휴대폰 번호</label>
                    <input type="tel" value={loginForm.phone} onChange={e => setLoginForm({ ...loginForm, phone: e.target.value })} required placeholder="01012345678" style={inputStyle} />
                  </div>
                  <div>
                    <label style={{ display: 'block', fontSize: 12, fontWeight: 700, color: C.textSub, marginBottom: 5 }}>비밀번호</label>
                    <input type="password" value={loginForm.password} onChange={e => setLoginForm({ ...loginForm, password: e.target.value })} required placeholder="4~6자리 숫자" maxLength={6} style={inputStyle} />
                  </div>
                  <button type="submit" disabled={loading}
                    style={{ padding: '13px', background: loading ? C.textMuted : C.accent, color: '#fff', border: 'none', borderRadius: 8, cursor: loading ? 'not-allowed' : 'pointer', fontSize: 15, fontWeight: 700, marginTop: 4 }}>
                    {loading ? '확인 중...' : '조회하기'}
                  </button>
                </form>
              ) : (
                <form onSubmit={handleRegister} style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
                  <div>
                    <label style={{ display: 'block', fontSize: 12, fontWeight: 700, color: C.textSub, marginBottom: 5 }}>이름</label>
                    <input value={regForm.name} onChange={e => setRegForm({ ...regForm, name: e.target.value })} required placeholder="홍길동" style={inputStyle} />
                  </div>
                  <div>
                    <label style={{ display: 'block', fontSize: 12, fontWeight: 700, color: C.textSub, marginBottom: 5 }}>휴대폰 번호</label>
                    <input type="tel" value={regForm.phone} onChange={e => setRegForm({ ...regForm, phone: e.target.value })} required placeholder="01012345678" style={inputStyle} />
                  </div>
                  <div>
                    <label style={{ display: 'block', fontSize: 12, fontWeight: 700, color: C.textSub, marginBottom: 5 }}>비밀번호 (4~6자리 숫자)</label>
                    <input type="password" value={regForm.password} onChange={e => setRegForm({ ...regForm, password: e.target.value })} required placeholder="4~6자리 숫자" maxLength={6} style={inputStyle} />
                  </div>
                  <button type="submit" disabled={loading}
                    style={{ padding: '13px', background: loading ? C.textMuted : C.accent, color: '#fff', border: 'none', borderRadius: 8, cursor: loading ? 'not-allowed' : 'pointer', fontSize: 15, fontWeight: 700, marginTop: 4 }}>
                    {loading ? '처리 중...' : '가입하기'}
                  </button>
                </form>
              )}
            </div>

            <div style={{ borderTop: `1px solid ${C.border}`, padding: '14px 28px', textAlign: 'center', fontSize: 13, color: C.textSub }}>
              회원이시라면?{' '}
              <Link to="/login" style={{ color: C.accent, fontWeight: 700, textDecoration: 'none' }}>회원 로그인</Link>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
