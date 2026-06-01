import { useState, useRef, useEffect } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { v4 as uuidv4 } from 'uuid'
import client from '../api/client'
import type { ApiResponse, PaymentResult, Reservation } from '../types/api'

interface LocationState {
  reservation: Reservation
}

export default function PaymentPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const state = location.state as LocationState | null
  const reservation = state?.reservation

  const [method, setMethod] = useState<'CARD' | 'TRANSFER'>('CARD')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const idempotencyKey = useRef(uuidv4())

  const expiresAtStr = reservation?.expiresAt ?? null
  const [expiresIn, setExpiresIn] = useState<number | null>(() =>
    expiresAtStr ? Math.max(0, Math.round((new Date(expiresAtStr).getTime() - Date.now()) / 1000)) : null
  )

  useEffect(() => {
    if (!reservation) navigate('/home')
  }, [reservation, navigate])

  useEffect(() => {
    if (!expiresAtStr) return
    const expMs = new Date(expiresAtStr).getTime()
    const id = setInterval(() => {
      setExpiresIn(Math.max(0, Math.round((expMs - Date.now()) / 1000)))
    }, 1000)
    return () => clearInterval(id)
  }, [expiresAtStr])

  if (!reservation) return null

  const handlePay = async () => {
    setError('')
    setLoading(true)
    try {
      const { data } = await client.post<ApiResponse<PaymentResult>>(
        '/api/payments',
        { reservationId: reservation.reservationId, amount: reservation.totalPrice, method },
        { headers: { 'Idempotency-Key': idempotencyKey.current } },
      )
      if (data.data?.status === 'COMPLETED') {
        navigate('/reservations', { state: { justPaid: true } })
      } else {
        setError('결제가 처리되지 않았습니다.')
      }
    } catch (err: unknown) {
      const resp = (err as { response?: { data?: ApiResponse<unknown> } })?.response?.data
      if (resp?.code === 'RATE_LIMITED') {
        const retryAfter = (err as { response?: { headers?: Record<string, string> } })?.response?.headers?.['retry-after']
        setError(`요청이 너무 많습니다. ${retryAfter ? retryAfter + '초 후 재시도' : '잠시 후 다시 시도'} 하세요.`)
      } else {
        setError(resp?.message ?? '결제 실패')
      }
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={styles.page}>
      <div style={styles.card}>
        <h2>결제</h2>
        <div style={styles.infoBox}>
          <div><strong>예약 번호</strong>: {reservation.reservationId}</div>
          <div><strong>좌석</strong>: {reservation.tickets.map(t => t.seatNumber).join(', ')}</div>
          <div><strong>결제 금액</strong>: {reservation.totalPrice.toLocaleString()}원</div>
          {expiresIn !== null && (
            <div style={{ color: expiresIn < 120 ? '#d32f2f' : '#555' }}>
              <strong>결제 만료</strong>: {Math.floor(expiresIn / 60)}분 {expiresIn % 60}초 남음
            </div>
          )}
        </div>
        <div style={styles.methodBox}>
          <label>
            <input type="radio" value="CARD" checked={method === 'CARD'} onChange={() => setMethod('CARD')} />{' '}
            신용카드
          </label>
          <label style={{ marginLeft: 20 }}>
            <input type="radio" value="TRANSFER" checked={method === 'TRANSFER'} onChange={() => setMethod('TRANSFER')} />{' '}
            계좌이체
          </label>
        </div>
        {error && <p style={styles.error}>{error}</p>}
        <button onClick={handlePay} disabled={loading} style={styles.btn}>
          {loading ? '결제 중...' : `${reservation.totalPrice.toLocaleString()}원 결제하기`}
        </button>
        <button onClick={() => navigate('/home')} style={styles.cancelBtn}>취소</button>
      </div>
    </div>
  )
}

const styles = {
  page: { display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', background: '#f5f5f5' } as React.CSSProperties,
  card: { background: '#fff', padding: 32, borderRadius: 8, boxShadow: '0 2px 8px rgba(0,0,0,.12)', width: 400 } as React.CSSProperties,
  infoBox: { background: '#f0f4ff', padding: 16, borderRadius: 6, marginBottom: 20, display: 'flex', flexDirection: 'column' as const, gap: 6 },
  methodBox: { marginBottom: 20 } as React.CSSProperties,
  btn: { display: 'block', width: '100%', padding: '12px', background: '#1a73e8', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 16, fontWeight: 600 } as React.CSSProperties,
  cancelBtn: { display: 'block', width: '100%', padding: '10px', background: '#fff', color: '#666', border: '1px solid #ccc', borderRadius: 4, cursor: 'pointer', fontSize: 14, marginTop: 8 } as React.CSSProperties,
  error: { color: '#d32f2f', background: '#fce4ec', padding: '8px 12px', borderRadius: 4, fontSize: 13, marginBottom: 12 } as React.CSSProperties,
}
