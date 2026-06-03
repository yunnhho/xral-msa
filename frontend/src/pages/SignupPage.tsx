import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import client from '../api/client'
import type { ApiResponse } from '../types/api'
import { C } from '../styles/theme'

const INPUT = {
  width: '100%', padding: '10px 12px', border: `1.5px solid ${C.border}`, borderRadius: 6,
  fontSize: 14, color: C.text, background: '#fff', boxSizing: 'border-box' as const,
  outline: 'none', transition: 'border-color 0.15s',
}
const LABEL = { display: 'block', fontSize: 12, fontWeight: 700, color: C.textSub, marginBottom: 5 }

export default function SignupPage() {
  const navigate = useNavigate()
  const [form, setForm] = useState({ email: '', password: '', name: '', phone: '', birthDate: '' })
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [focused, setFocused] = useState('')

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm({ ...form, [k]: e.target.value })

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      await client.post<ApiResponse<unknown>>('/api/auth/signup', form, {
        headers: { 'X-Captcha-Token': btoa(String(Date.now())) },
      })
      navigate('/login', { state: { signupSuccess: true } })
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: ApiResponse<unknown> } })?.response?.data?.message
      setError(msg ?? '회원가입에 실패했습니다.')
    } finally {
      setLoading(false)
    }
  }

  const inputStyle = (k: string) => ({ ...INPUT, borderColor: focused === k ? C.accent : C.border })

  return (
    <div style={{ minHeight: '100vh', background: C.bg, display: 'flex', flexDirection: 'column' }}>
      <div style={{ background: C.accent, padding: '14px 24px', display: 'flex', alignItems: 'center', gap: 8 }}>
        <Link to="/login" style={{ color: 'rgba(255,255,255,.7)', fontSize: 13, textDecoration: 'none' }}>← 로그인</Link>
        <span style={{ color: 'rgba(255,255,255,.3)', margin: '0 4px' }}>|</span>
        <span style={{ color: '#fff', fontSize: 15, fontWeight: 700 }}>XRail</span>
        <span style={{ color: 'rgba(255,255,255,.5)', fontSize: 12 }}>회원가입</span>
      </div>

      <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '32px 20px' }}>
        <div style={{ width: '100%', maxWidth: 460 }}>
          <div style={{ background: '#fff', borderRadius: 12, boxShadow: '0 4px 24px rgba(0,30,87,.1)', overflow: 'hidden' }}>
            <div style={{ padding: '28px 32px 20px', borderBottom: `1px solid ${C.border}` }}>
              <h2 style={{ margin: 0, fontSize: 20, fontWeight: 800, color: C.text }}>회원가입</h2>
              <p style={{ margin: '6px 0 0', fontSize: 13, color: C.textSub }}>XRail 회원이 되시면 더 편리하게 예매하실 수 있습니다.</p>
            </div>

            <form onSubmit={handleSubmit} style={{ padding: '24px 32px', display: 'flex', flexDirection: 'column', gap: 16 }}>
              {error && (
                <div style={{ background: C.dangerBg, border: `1px solid #fca5a5`, color: C.danger, borderRadius: 6, padding: '10px 14px', fontSize: 13 }}>
                  ⚠ {error}
                </div>
              )}

              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
                <div style={{ gridColumn: '1 / -1' }}>
                  <label style={LABEL}>이메일 *</label>
                  <input type="email" value={form.email} onChange={set('email')} required placeholder="example@email.com"
                    onFocus={() => setFocused('email')} onBlur={() => setFocused('')}
                    style={inputStyle('email')} />
                </div>
                <div style={{ gridColumn: '1 / -1' }}>
                  <label style={LABEL}>비밀번호 *</label>
                  <input type="password" value={form.password} onChange={set('password')} required placeholder="8자 이상, 영문/숫자/특수문자 조합"
                    onFocus={() => setFocused('password')} onBlur={() => setFocused('')}
                    style={inputStyle('password')} />
                </div>
                <div>
                  <label style={LABEL}>이름 *</label>
                  <input type="text" value={form.name} onChange={set('name')} required placeholder="홍길동"
                    onFocus={() => setFocused('name')} onBlur={() => setFocused('')}
                    style={inputStyle('name')} />
                </div>
                <div>
                  <label style={LABEL}>생년월일 *</label>
                  <input type="text" value={form.birthDate} onChange={set('birthDate')} required placeholder="19900101"
                    maxLength={8} onFocus={() => setFocused('birthDate')} onBlur={() => setFocused('')}
                    style={inputStyle('birthDate')} />
                </div>
                <div style={{ gridColumn: '1 / -1' }}>
                  <label style={LABEL}>휴대폰 번호 *</label>
                  <input type="tel" value={form.phone} onChange={set('phone')} required placeholder="01012345678 (하이픈 없이)"
                    onFocus={() => setFocused('phone')} onBlur={() => setFocused('')}
                    style={inputStyle('phone')} />
                </div>
              </div>

              <button type="submit" disabled={loading}
                style={{ padding: '13px', background: loading ? C.textMuted : C.accent, color: '#fff', border: 'none', borderRadius: 8, cursor: loading ? 'not-allowed' : 'pointer', fontSize: 15, fontWeight: 700, marginTop: 4 }}>
                {loading ? '처리 중...' : '가입하기'}
              </button>
            </form>

            <div style={{ padding: '16px 32px', borderTop: `1px solid ${C.border}`, textAlign: 'center', fontSize: 13, color: C.textSub }}>
              이미 계정이 있으신가요?{' '}
              <Link to="/login" style={{ color: C.accent, fontWeight: 700, textDecoration: 'none' }}>로그인</Link>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
