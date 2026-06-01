import { useState, useRef, useEffect } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { v4 as uuidv4 } from 'uuid'
import client from '../api/client'
import type { ApiResponse, PaymentResult, Reservation } from '../types/api'
import { C } from '../styles/theme'
import NavBar from '../components/NavBar'
import BookingSteps from '../components/BookingSteps'

interface CouponResult { discountAmount: number; finalAmount: number; description: string; code: string }
interface LocationState { reservation: Reservation }

const METHODS = [
  { id: 'CARD' as const, icon: '💳', label: '신용/체크카드', desc: '국내외 모든 카드' },
  { id: 'TRANSFER' as const, icon: '🏦', label: '계좌이체', desc: '실시간 계좌이체' },
]

export default function PaymentPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const state = location.state as LocationState | null
  const reservation = state?.reservation

  const [method, setMethod] = useState<'CARD' | 'TRANSFER'>('CARD')
  const [loading, setLoading] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [error, setError] = useState('')
  const [couponCode, setCouponCode] = useState('')
  const [couponInput, setCouponInput] = useState('')
  const [couponResult, setCouponResult] = useState<CouponResult | null>(null)
  const [couponError, setCouponError] = useState('')
  const [couponLoading, setCouponLoading] = useState(false)
  const idempotencyKey = useRef(uuidv4())

  const expiresAtStr = reservation?.expiresAt ?? null
  const [expiresIn, setExpiresIn] = useState<number | null>(() =>
    expiresAtStr ? Math.max(0, Math.round((new Date(expiresAtStr).getTime() - Date.now()) / 1000)) : null
  )

  useEffect(() => { if (!reservation) navigate('/home') }, [reservation, navigate])

  useEffect(() => {
    if (!expiresAtStr) return
    const expMs = new Date(expiresAtStr).getTime()
    const id = setInterval(() => {
      const remaining = Math.max(0, Math.round((expMs - Date.now()) / 1000))
      setExpiresIn(remaining)
      if (remaining === 0) clearInterval(id)
    }, 1000)
    return () => clearInterval(id)
  }, [expiresAtStr])

  if (!reservation) return null

  const isUrgent = expiresIn !== null && expiresIn < 120
  const mins = expiresIn !== null ? Math.floor(expiresIn / 60) : 0
  const secs = expiresIn !== null ? expiresIn % 60 : 0

  const originalAmount = reservation.totalPrice
  const discountAmount = couponResult?.discountAmount ?? 0
  const finalAmount = originalAmount - discountAmount

  const applyCoupon = async () => {
    if (!couponInput.trim()) return
    setCouponError('')
    setCouponLoading(true)
    try {
      const { data } = await client.get<ApiResponse<CouponResult>>(
        `/api/coupons/validate?code=${encodeURIComponent(couponInput.trim())}&amount=${originalAmount}`
      )
      if (data.data) {
        setCouponResult(data.data)
        setCouponCode(data.data.code)
        setCouponInput('')
      }
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: ApiResponse<unknown> } })?.response?.data?.message
      setCouponError(msg ?? '유효하지 않은 쿠폰 코드입니다.')
    } finally {
      setCouponLoading(false)
    }
  }

  const removeCoupon = () => { setCouponResult(null); setCouponCode(''); setCouponError('') }

  const handlePay = async () => {
    setError('')
    setLoading(true)
    try {
      const { data } = await client.post<ApiResponse<PaymentResult>>(
        '/api/payments',
        { reservationId: reservation.reservationId, amount: originalAmount, method, couponCode: couponCode || null },
        { headers: { 'Idempotency-Key': idempotencyKey.current } },
      )
      if (data.data?.status === 'COMPLETED') {
        navigate('/reservations', { state: { justPaid: true, reservationId: reservation.reservationId } })
      } else {
        setError('결제가 처리되지 않았습니다.')
      }
    } catch (err: unknown) {
      const resp = (err as { response?: { data?: ApiResponse<unknown>; headers?: Record<string, string> } })?.response
      if (resp?.data?.code === 'RATE_LIMITED') {
        const retryAfter = resp?.headers?.['retry-after']
        setError(`요청이 너무 많습니다. ${retryAfter ? retryAfter + '초 후 재시도' : '잠시 후 다시 시도'} 하세요.`)
      } else {
        setError(resp?.data?.message ?? '결제 실패')
      }
    } finally { setLoading(false) }
  }

  // Bug #3 fix: 예매 취소 = 실제 DELETE API 호출로 좌석/점유 모두 해제
  const handleCancel = async () => {
    if (!confirm('예약을 취소하시겠습니까?\n취소 시 선택한 좌석은 즉시 반납됩니다.')) return
    setCancelling(true)
    try {
      await client.delete(`/api/reservations/${reservation!.reservationId}`)
      navigate('/home')
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: ApiResponse<unknown> } })?.response?.data?.message
      setError(msg ?? '취소 실패. 다시 시도해주세요.')
    } finally { setCancelling(false) }
  }

  return (
    <div style={{ minHeight: '100vh', background: C.bg }}>
      <NavBar />
      <BookingSteps current={3} />

      {/* 만료 타이머 배너 */}
      {expiresIn !== null && (
        <div style={{ background: isUrgent ? '#fff1f0' : C.accentLight, borderBottom: `1px solid ${isUrgent ? '#fca5a5' : '#c7d7f5'}`, padding: '8px 20px' }}>
          <div style={{ maxWidth: 860, margin: '0 auto', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <span style={{ fontSize: 13, color: isUrgent ? C.danger : C.accent, fontWeight: 600 }}>
              {isUrgent ? '⚠ 결제 시간이 얼마 남지 않았습니다!' : '⏱ 결제 가능 시간'}
            </span>
            <span style={{ fontSize: 22, fontWeight: 900, color: isUrgent ? C.danger : C.accent, fontFamily: 'monospace', letterSpacing: 1 }}>
              {String(mins).padStart(2, '0')}:{String(secs).padStart(2, '0')}
            </span>
          </div>
        </div>
      )}

      <div style={{ maxWidth: 860, margin: '20px auto', padding: '0 20px 40px', display: 'grid', gridTemplateColumns: '1fr 300px', gap: 20, alignItems: 'start' }}>
        {/* 왼쪽 */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          {/* 예매 정보 */}
          <div style={{ background: '#fff', borderRadius: 10, boxShadow: '0 2px 12px rgba(0,30,87,.08)', overflow: 'hidden' }}>
            <div style={{ padding: '13px 20px', background: C.accent, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <h3 style={{ margin: 0, fontSize: 14, fontWeight: 700, color: '#fff' }}>예매 정보</h3>
              <span style={{ fontSize: 12, color: 'rgba(255,255,255,.7)' }}>예약번호 No.{reservation.reservationId}</span>
            </div>
            <div style={{ padding: '18px 20px' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr style={{ borderBottom: `2px solid ${C.border}` }}>
                    {['좌석', '구분', '운임'].map((h, i) => (
                      <th key={h} style={{ padding: '7px 0', fontSize: 11, fontWeight: 700, color: C.textMuted, textAlign: i === 2 ? 'right' : 'left', textTransform: 'uppercase', letterSpacing: 0.4 }}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {reservation.tickets.map((t, i) => (
                    <tr key={t.ticketId} style={{ borderBottom: `1px solid ${C.border}` }}>
                      <td style={{ padding: '11px 0' }}>
                        <span style={{ background: C.accentLight, color: C.accent, borderRadius: 4, padding: '3px 8px', fontSize: 13, fontWeight: 800 }}>{t.seatNumber}</span>
                      </td>
                      <td style={{ padding: '11px 0', fontSize: 12, color: C.textSub }}>좌석 {i + 1}</td>
                      <td style={{ padding: '11px 0', textAlign: 'right', fontSize: 14, fontWeight: 700 }}>{t.price.toLocaleString()}원</td>
                    </tr>
                  ))}
                </tbody>
                <tfoot>
                  <tr>
                    <td colSpan={2} style={{ paddingTop: 12, fontSize: 13, fontWeight: 700 }}>소계</td>
                    <td style={{ paddingTop: 12, textAlign: 'right', fontSize: 16, fontWeight: 800 }}>{originalAmount.toLocaleString()}원</td>
                  </tr>
                </tfoot>
              </table>
            </div>
          </div>

          {/* 쿠폰 */}
          <div style={{ background: '#fff', borderRadius: 10, boxShadow: '0 2px 12px rgba(0,30,87,.08)', padding: '18px 20px' }}>
            <h3 style={{ margin: '0 0 14px', fontSize: 14, fontWeight: 700, color: C.text }}>쿠폰 / 할인</h3>

            {couponResult ? (
              <div style={{ background: '#f0fdf4', border: '1px solid #86efac', borderRadius: 8, padding: '12px 14px', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <div>
                  <p style={{ margin: 0, fontSize: 13, fontWeight: 700, color: C.success }}>✓ {couponResult.code} 적용됨</p>
                  <p style={{ margin: '3px 0 0', fontSize: 12, color: C.textSub }}>{couponResult.description}</p>
                </div>
                <div style={{ textAlign: 'right' }}>
                  <p style={{ margin: 0, fontSize: 16, fontWeight: 800, color: C.success }}>-{discountAmount.toLocaleString()}원</p>
                  <button onClick={removeCoupon} style={{ fontSize: 11, color: C.textMuted, background: 'none', border: 'none', cursor: 'pointer', padding: 0, marginTop: 2 }}>제거</button>
                </div>
              </div>
            ) : (
              <div>
                <div style={{ display: 'flex', gap: 8 }}>
                  <input
                    value={couponInput}
                    onChange={e => setCouponInput(e.target.value.toUpperCase())}
                    onKeyDown={e => e.key === 'Enter' && applyCoupon()}
                    placeholder="쿠폰 코드 입력 (예: XRAIL10)"
                    style={{ flex: 1, padding: '10px 12px', border: `1.5px solid ${couponError ? '#fca5a5' : C.border}`, borderRadius: 6, fontSize: 14, color: C.text, boxSizing: 'border-box', letterSpacing: 1 }}
                  />
                  <button onClick={applyCoupon} disabled={couponLoading || !couponInput.trim()}
                    style={{ padding: '10px 16px', background: couponInput.trim() ? C.accent : '#e5e7eb', color: couponInput.trim() ? '#fff' : C.textMuted, border: 'none', borderRadius: 6, cursor: couponInput.trim() ? 'pointer' : 'not-allowed', fontSize: 13, fontWeight: 700, flexShrink: 0 }}>
                    {couponLoading ? '확인 중...' : '적용'}
                  </button>
                </div>
                {couponError && <p style={{ color: C.danger, fontSize: 12, margin: '6px 0 0' }}>⚠ {couponError}</p>}
                <p style={{ fontSize: 11, color: C.textMuted, margin: '8px 0 0' }}>사용 가능: XRAIL10, SUMMER5000, WELCOME3000, KTX20</p>
              </div>
            )}
          </div>

          {/* 결제수단 */}
          <div style={{ background: '#fff', borderRadius: 10, boxShadow: '0 2px 12px rgba(0,30,87,.08)', padding: '18px 20px' }}>
            <h3 style={{ margin: '0 0 14px', fontSize: 14, fontWeight: 700, color: C.text }}>결제 수단</h3>
            <div style={{ display: 'flex', gap: 10 }}>
              {METHODS.map(m => (
                <label key={m.id}
                  style={{ flex: 1, display: 'flex', alignItems: 'center', gap: 10, padding: '14px', border: `2px solid ${method === m.id ? C.accent : C.border}`, borderRadius: 8, cursor: 'pointer', background: method === m.id ? C.accentLight : '#fff', transition: 'all 0.15s' }}>
                  <input type="radio" name="method" value={m.id} checked={method === m.id} onChange={() => setMethod(m.id)} style={{ display: 'none' }} />
                  <span style={{ fontSize: 22 }}>{m.icon}</span>
                  <div style={{ flex: 1 }}>
                    <div style={{ fontSize: 13, fontWeight: 700, color: method === m.id ? C.accent : C.text }}>{m.label}</div>
                    <div style={{ fontSize: 11, color: C.textMuted }}>{m.desc}</div>
                  </div>
                  <div style={{ width: 18, height: 18, borderRadius: '50%', border: `2px solid ${method === m.id ? C.accent : C.border}`, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
                    {method === m.id && <div style={{ width: 9, height: 9, borderRadius: '50%', background: C.accent }} />}
                  </div>
                </label>
              ))}
            </div>
          </div>
        </div>

        {/* 오른쪽: 결제 금액 요약 */}
        <div style={{ position: 'sticky', top: 20 }}>
          <div style={{ background: '#fff', borderRadius: 10, boxShadow: '0 2px 12px rgba(0,30,87,.08)', overflow: 'hidden' }}>
            <div style={{ padding: '13px 18px', background: C.bg, borderBottom: `1px solid ${C.border}` }}>
              <h3 style={{ margin: 0, fontSize: 13, fontWeight: 700, color: C.text }}>최종 결제 금액</h3>
            </div>
            <div style={{ padding: '18px' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6, fontSize: 13, color: C.textSub }}>
                <span>좌석 {reservation.tickets.length}매</span>
                <span>{originalAmount.toLocaleString()}원</span>
              </div>
              {discountAmount > 0 && (
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6, fontSize: 13, color: C.success, fontWeight: 600 }}>
                  <span>쿠폰 할인</span>
                  <span>-{discountAmount.toLocaleString()}원</span>
                </div>
              )}
              <div style={{ borderTop: `1.5px solid ${C.border}`, paddingTop: 12, marginTop: 10, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <span style={{ fontSize: 14, fontWeight: 700 }}>결제 금액</span>
                <div style={{ textAlign: 'right' }}>
                  {discountAmount > 0 && <div style={{ fontSize: 11, color: C.textMuted, textDecoration: 'line-through' }}>{originalAmount.toLocaleString()}원</div>}
                  <span style={{ fontSize: 24, fontWeight: 900, color: C.accent }}>{finalAmount.toLocaleString()}원</span>
                </div>
              </div>

              {error && (
                <div style={{ background: C.dangerBg, color: C.danger, borderRadius: 6, padding: '10px 12px', fontSize: 13, marginTop: 14 }}>
                  {error}
                </div>
              )}

              <button onClick={handlePay} disabled={loading || cancelling}
                style={{ width: '100%', padding: '15px', background: loading ? C.textMuted : C.cta, color: '#fff', border: 'none', borderRadius: 8, cursor: loading || cancelling ? 'not-allowed' : 'pointer', fontSize: 15, fontWeight: 900, marginTop: 14 }}>
                {loading ? '결제 처리 중...' : `${finalAmount.toLocaleString()}원 결제`}
              </button>

              {/* 나중에 결제: 예약 유지, 예매 내역에서 결제 */}
              <button onClick={() => navigate('/reservations')} disabled={loading || cancelling}
                style={{ width: '100%', padding: '10px', background: '#fff', color: C.textSub, border: `1px solid ${C.border}`, borderRadius: 8, cursor: 'pointer', fontSize: 13, marginTop: 8 }}>
                나중에 결제
              </button>

              {/* 예매 취소: DELETE API → 좌석 즉시 반납 */}
              <button onClick={handleCancel} disabled={loading || cancelling}
                style={{ width: '100%', padding: '10px', background: '#fff', color: C.danger, border: `1px solid ${C.danger}`, borderRadius: 8, cursor: loading || cancelling ? 'not-allowed' : 'pointer', fontSize: 13, marginTop: 6 }}>
                {cancelling ? '취소 처리 중...' : '예매 취소'}
              </button>

              <p style={{ fontSize: 11, color: C.textMuted, textAlign: 'center', marginTop: 12, lineHeight: 1.6 }}>
                테스트 환경 · 실제 결제 없음
              </p>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
