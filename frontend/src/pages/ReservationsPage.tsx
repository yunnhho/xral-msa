import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import client from '../api/client'
import type { ApiResponse, PageResponse, Reservation } from '../types/api'

const STATUS_LABEL: Record<string, string> = {
  PENDING: '결제 대기',
  PAID: '결제 완료',
  CANCELLED: '취소',
}

const STATUS_COLOR: Record<string, string> = {
  PENDING: '#f57c00',
  PAID: '#388e3c',
  CANCELLED: '#9e9e9e',
}

export default function ReservationsPage() {
  const navigate = useNavigate()
  const [reservations, setReservations] = useState<Reservation[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [cancelling, setCancelling] = useState<number | null>(null)

  const load = () => {
    setLoading(true)
    client
      .get<ApiResponse<PageResponse<Reservation>>>('/api/reservations', { params: { page: 0, size: 20 } })
      .then(({ data }) => setReservations(data.data?.content ?? []))
      .catch((err) => {
        const msg = (err as { response?: { data?: ApiResponse<unknown> } })?.response?.data?.message
        setError(msg ?? '조회 실패')
      })
      .finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [])

  const handleCancel = async (reservationId: number) => {
    if (!confirm('예약을 취소하시겠습니까?')) return
    setCancelling(reservationId)
    try {
      await client.delete(`/api/reservations/${reservationId}`)
      load()
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: ApiResponse<unknown> } })?.response?.data?.message
      alert(msg ?? '취소 실패')
    } finally {
      setCancelling(null)
    }
  }

  return (
    <div style={styles.page}>
      <div style={styles.container}>
        <div style={styles.headerRow}>
          <h2>내 예매 내역</h2>
          <button onClick={() => navigate('/home')} style={styles.backBtn}>← 홈으로</button>
        </div>
        {error && <p style={styles.error}>{error}</p>}
        {loading ? (
          <p>로딩 중...</p>
        ) : reservations.length === 0 ? (
          <p style={{ color: '#555', textAlign: 'center', marginTop: 40 }}>예매 내역이 없습니다.</p>
        ) : (
          reservations.map(r => (
            <div key={r.reservationId} style={styles.item}>
              <div style={styles.itemHeader}>
                <span style={{ fontWeight: 700 }}>예약 #{r.reservationId}</span>
                <span style={{ color: STATUS_COLOR[r.status] ?? '#555', fontWeight: 600 }}>
                  {STATUS_LABEL[r.status] ?? r.status}
                </span>
              </div>
              <div style={styles.itemBody}>
                <div>좌석: {r.tickets.map(t => t.seatNumber).join(', ')}</div>
                <div>금액: {r.totalPrice.toLocaleString()}원</div>
                <div>예약일: {new Date(r.reservedAt).toLocaleString('ko-KR')}</div>
                {r.expiresAt && r.status === 'PENDING' && (
                  <div style={{ color: '#f57c00' }}>
                    만료: {new Date(r.expiresAt).toLocaleString('ko-KR')}
                  </div>
                )}
              </div>
              {(r.status === 'PENDING' || r.status === 'PAID') && (
                <button
                  onClick={() => handleCancel(r.reservationId)}
                  disabled={cancelling === r.reservationId}
                  style={styles.cancelBtn}
                >
                  {cancelling === r.reservationId ? '취소 중...' : '예약 취소'}
                </button>
              )}
              {r.status === 'PENDING' && (
                <button
                  onClick={() => navigate('/payment', { state: { reservation: r } })}
                  style={styles.payBtn}
                >
                  결제하기
                </button>
              )}
            </div>
          ))
        )}
      </div>
    </div>
  )
}

const styles = {
  page: { minHeight: '100vh', background: '#f5f5f5', padding: 24 } as React.CSSProperties,
  container: { maxWidth: 640, margin: '0 auto' } as React.CSSProperties,
  headerRow: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 } as React.CSSProperties,
  backBtn: { background: 'none', border: '1px solid #ccc', padding: '6px 12px', borderRadius: 4, cursor: 'pointer' } as React.CSSProperties,
  item: { background: '#fff', borderRadius: 8, boxShadow: '0 1px 4px rgba(0,0,0,.1)', padding: 20, marginBottom: 16 } as React.CSSProperties,
  itemHeader: { display: 'flex', justifyContent: 'space-between', marginBottom: 10 } as React.CSSProperties,
  itemBody: { display: 'flex', flexDirection: 'column' as const, gap: 4, fontSize: 14, color: '#444', marginBottom: 12 },
  cancelBtn: { padding: '6px 16px', background: '#fff', border: '1px solid #ccc', color: '#555', borderRadius: 4, cursor: 'pointer', marginRight: 8 } as React.CSSProperties,
  payBtn: { padding: '6px 16px', background: '#1a73e8', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer' } as React.CSSProperties,
  error: { color: '#d32f2f', fontSize: 13 } as React.CSSProperties,
}
