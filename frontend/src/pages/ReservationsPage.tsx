import { useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import client from '../api/client'
import type { ApiResponse, Reservation } from '../types/api'
import { C } from '../styles/theme'
import NavBar from '../components/NavBar'

const STATUS: Record<string, { label: string; color: string; bar: string; bg: string }> = {
  PENDING:   { label: '결제 대기', color: '#b45309', bar: '#f59e0b', bg: '#fffbeb' },
  PAID:      { label: '결제 완료', color: '#065f46', bar: '#10b981', bg: '#ecfdf5' },
  CANCELLED: { label: '취소',     color: '#64748b', bar: '#cbd5e1', bg: '#f8fafc' },
}

export default function ReservationsPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const { justPaid, reservationId: paidId } = (location.state as { justPaid?: boolean; reservationId?: number } | null) ?? {}

  const [reservations, setReservations] = useState<Reservation[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [cancelling, setCancelling] = useState<number | null>(null)
  const [paidConfirmed, setPaidConfirmed] = useState(false)
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)

  const load = () => {
    setLoading(true)
    client
      .get<ApiResponse<Reservation[]>>('/api/reservations')
      .then(({ data }) => setReservations(data.data ?? []))
      .catch(err => setError((err as { response?: { data?: ApiResponse<unknown> } })?.response?.data?.message ?? '조회 실패'))
      .finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [])

  // justPaid: 방금 결제한 예약이 PAID로 업데이트될 때까지 폴링
  useEffect(() => {
    if (!justPaid || !paidId) return
    let attempts = 0
    pollRef.current = setInterval(() => {
      attempts++
      client.get<ApiResponse<Reservation[]>>('/api/reservations').then(({ data }) => {
        const list = data.data ?? []
        setReservations(list)
        const paid = list.find(r => r.reservationId === paidId && r.status === 'PAID')
        if (paid || attempts >= 10) {
          clearInterval(pollRef.current!)
          if (paid) setPaidConfirmed(true)
        }
      }).catch(() => { if (attempts >= 10) clearInterval(pollRef.current!) })
    }, 2000)
    return () => { if (pollRef.current) clearInterval(pollRef.current) }
  }, [justPaid, paidId])

  const handleCancel = async (reservationId: number) => {
    if (!confirm('예약을 취소하시겠습니까?')) return
    setCancelling(reservationId)
    try {
      await client.delete(`/api/reservations/${reservationId}`)
      load()
    } catch (err: unknown) {
      alert((err as { response?: { data?: ApiResponse<unknown> } })?.response?.data?.message ?? '취소 실패')
    } finally { setCancelling(null) }
  }

  const isExpired = (r: Reservation) =>
    r.status === 'PENDING' && r.expiresAt != null && new Date(r.expiresAt) < new Date()

  const active = reservations.filter(r => r.status !== 'CANCELLED' && !isExpired(r))
  const cancelled = reservations.filter(r => r.status === 'CANCELLED')

  return (
    <div style={{ minHeight: '100vh', background: C.bg }}>
      <NavBar />

      {/* 페이지 헤더 */}
      <div style={{ background: C.surface, borderBottom: `1px solid ${C.border}` }}>
        <div style={{ maxWidth: 800, margin: '0 auto', padding: '18px 24px' }}>
          <h2 style={{ margin: 0, fontSize: 20, fontWeight: 800, color: C.text, letterSpacing: -0.3 }}>예매 내역</h2>
          {!loading && <p style={{ margin: '3px 0 0', fontSize: 13, color: C.textMuted }}>총 {reservations.length}건</p>}
        </div>
      </div>

      <div style={{ maxWidth: 800, margin: '0 auto', padding: '20px 24px 48px' }}>
        {/* 결제 완료 알림 배너 */}
        {justPaid && !paidConfirmed && (
          <div style={{ background: C.warningBg, border: `1px solid #fde68a`, borderRadius: 6, padding: '12px 16px', marginBottom: 14, display: 'flex', alignItems: 'center', gap: 10, fontSize: 13, color: C.warning, fontWeight: 600 }}>
            <div style={{ width: 14, height: 14, border: '2px solid currentColor', borderTop: '2px solid transparent', borderRadius: '50%', flexShrink: 0, animation: 'spin 0.8s linear infinite' }} />
            결제를 확인 중입니다...
          </div>
        )}
        {paidConfirmed && (
          <div className="fade-in" style={{ background: C.successBg, border: `1px solid #6ee7b7`, borderRadius: 6, padding: '12px 16px', marginBottom: 14, fontSize: 13, color: C.success, fontWeight: 600 }}>
            결제가 완료되었습니다.
          </div>
        )}

        {error && <div style={{ background: C.dangerBg, color: C.danger, borderRadius: 6, padding: '12px 16px', marginBottom: 14, fontSize: 14 }}>{error}</div>}

        {loading ? (
          <div style={{ background: C.surface, borderRadius: 6, padding: '48px 24px', textAlign: 'center', border: `1px solid ${C.border}` }}>
            <div style={{ width: 36, height: 36, border: `3px solid ${C.bgAlt}`, borderTop: `3px solid ${C.accent}`, borderRadius: '50%', margin: '0 auto 12px', animation: 'spin 0.8s linear infinite' }} />
            <p style={{ color: C.textMuted, fontSize: 14, margin: 0 }}>불러오는 중...</p>
          </div>
        ) : reservations.length === 0 ? (
          <div style={{ background: C.surface, border: `1px solid ${C.border}`, borderRadius: 6, padding: '56px 24px', textAlign: 'center' }}>
            <p style={{ color: C.textSub, fontSize: 15, margin: '0 0 16px' }}>예매 내역이 없습니다.</p>
            <button onClick={() => navigate('/home')}
              style={{ padding: '10px 22px', background: C.accent, color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 13, fontWeight: 700 }}>
              열차 예매하기
            </button>
          </div>
        ) : (
          <>
            {active.length > 0 && (
              <div style={{ marginBottom: 24 }}>
                <p style={{ fontSize: 11, fontWeight: 700, color: C.textMuted, margin: '0 0 10px', textTransform: 'uppercase', letterSpacing: 0.8 }}>진행 중 ({active.length})</p>
                {active.map(r => <ResvCard key={r.reservationId} r={r} onCancel={handleCancel} onPay={() => navigate('/payment', { state: { reservation: r } })} cancelling={cancelling} highlight={r.reservationId === paidId} />)}
              </div>
            )}
            {cancelled.length > 0 && (
              <div>
                <p style={{ fontSize: 11, fontWeight: 700, color: C.textMuted, margin: '0 0 10px', textTransform: 'uppercase', letterSpacing: 0.8 }}>취소됨 ({cancelled.length})</p>
                {cancelled.map(r => <ResvCard key={r.reservationId} r={r} onCancel={handleCancel} onPay={() => {}} cancelling={cancelling} highlight={false} />)}
              </div>
            )}
          </>
        )}
      </div>
      <style>{`@keyframes spin { to { transform: rotate(360deg) } }`}</style>
    </div>
  )
}

function ResvCard({ r, onCancel, onPay, cancelling, highlight }: {
  r: Reservation; onCancel: (id: number) => void; onPay: () => void
  cancelling: number | null; highlight: boolean
}) {
  const meta = STATUS[r.status] ?? STATUS.CANCELLED
  const isPending = r.status === 'PENDING'
  const isPaid = r.status === 'PAID'
  const isCancelled = r.status === 'CANCELLED'

  return (
    <div className={highlight ? 'fade-in' : ''} style={{ background: C.surface, border: `1px solid ${highlight ? C.accent : C.border}`, borderRadius: 6, marginBottom: 8, overflow: 'hidden', opacity: isCancelled ? 0.55 : 1 }}>
      {/* 상단 상태 바 */}
      <div style={{ height: 2, background: meta.bar }} />

      <div style={{ padding: '14px 18px' }}>
        {/* 헤더 */}
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 10 }}>
          <span style={{ fontSize: 12, fontWeight: 700, color: C.textMuted, fontFamily: 'monospace', letterSpacing: 0.5 }}>
            No.{r.reservationId}
          </span>
          <span style={{ fontSize: 11, fontWeight: 700, padding: '3px 9px', borderRadius: 3, background: meta.bg, color: meta.color }}>
            {meta.label}
          </span>
        </div>

        {/* 좌석 + 금액 */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '10px 12px', background: C.bg, borderRadius: 4, marginBottom: 10 }}>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 5, flex: 1 }}>
            {r.tickets.map(t => (
              <span key={t.ticketId}
                style={{ background: isCancelled ? C.bgAlt : C.accentLight, color: isCancelled ? C.textMuted : C.accent, borderRadius: 3, padding: '3px 8px', fontSize: 13, fontWeight: 800 }}>
                {t.seatNumber}
              </span>
            ))}
            {r.tickets.length > 1 && <span style={{ fontSize: 11, color: C.textMuted, alignSelf: 'center' }}>{r.tickets.length}매</span>}
          </div>
          <span style={{ fontSize: 18, fontWeight: 900, color: isCancelled ? C.textMuted : C.text, letterSpacing: -0.5 }}>
            {r.totalPrice.toLocaleString()}<span style={{ fontSize: 11, fontWeight: 400, color: C.textMuted }}>원</span>
          </span>
        </div>

        {/* 날짜 */}
        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 11, color: C.textMuted, marginBottom: (isPending || isPaid) ? 10 : 0 }}>
          <span>예약 {new Date(r.reservedAt).toLocaleString('ko-KR', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })}</span>
          {r.expiresAt && isPending && (
            <span style={{ color: C.warning, fontWeight: 600 }}>
              만료 {new Date(r.expiresAt).toLocaleString('ko-KR', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })}
            </span>
          )}
        </div>

        {/* 버튼 */}
        {(isPending || isPaid) && (
          <div style={{ display: 'flex', gap: 6, borderTop: `1px solid ${C.border}`, paddingTop: 10 }}>
            {isPending && (
              <button onClick={onPay}
                style={{ flex: 1, padding: '9px', background: C.accent, color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 13, fontWeight: 700 }}>
                결제하기
              </button>
            )}
            <button onClick={() => onCancel(r.reservationId)} disabled={cancelling === r.reservationId}
              style={{ flex: isPending ? 'none' : 1, padding: '9px 16px', background: C.surface, color: C.danger, border: `1px solid ${C.danger}`, borderRadius: 4, cursor: cancelling === r.reservationId ? 'not-allowed' : 'pointer', fontSize: 13, fontWeight: 600 }}>
              {cancelling === r.reservationId ? '처리 중...' : '예약 취소'}
            </button>
          </div>
        )}
      </div>
    </div>
  )
}
