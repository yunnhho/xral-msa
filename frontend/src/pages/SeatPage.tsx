import { useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { v4 as uuidv4 } from 'uuid'
import client from '../api/client'
import type { ApiResponse, CarriageInfo, Reservation, Schedule, SeatsResponse } from '../types/api'

interface LocationState {
  schedule: Schedule
  departureStationId: string
  arrivalStationId: string
  queueToken: string
}

export default function SeatPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const state = location.state as LocationState | null

  const [carriages, setCarriages] = useState<CarriageInfo[]>([])
  const [selected, setSelected] = useState<Set<number>>(new Set())
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')
  const idempotencyKey = useRef(uuidv4())

  useEffect(() => {
    if (!state?.schedule) { navigate('/home'); return }
    const { schedule, departureStationId, arrivalStationId } = state
    const controller = new AbortController()
    client
      .get<ApiResponse<SeatsResponse>>(`/api/schedules/${schedule.scheduleId}/seats`, {
        params: { departureStationId, arrivalStationId },
        signal: controller.signal,
      })
      .then(({ data }) => setCarriages(data.data?.carriages ?? []))
      .catch((err) => { if (!controller.signal.aborted) setError(String(err)) })
      .finally(() => setLoading(false))
    return () => controller.abort()
  }, [state, navigate])

  const toggleSeat = (seatId: number) => {
    setSelected(prev => {
      const next = new Set(prev)
      if (next.has(seatId)) next.delete(seatId)
      else next.add(seatId)
      return next
    })
  }

  const handleReserve = async () => {
    if (selected.size === 0) { setError('좌석을 선택하세요.'); return }
    setError('')
    setSubmitting(true)
    try {
      const { data } = await client.post<ApiResponse<Reservation>>(
        '/api/reservations',
        {
          scheduleId: state!.schedule.scheduleId,
          departureStationId: Number(state!.departureStationId),
          arrivalStationId: Number(state!.arrivalStationId),
          seatIds: Array.from(selected),
        },
        {
          headers: {
            'X-Queue-Token': state!.queueToken,
            'Idempotency-Key': idempotencyKey.current,
          },
        },
      )
      if (data.data) {
        navigate('/payment', { state: { reservation: data.data } })
      }
    } catch (err: unknown) {
      const resp = (err as { response?: { data?: ApiResponse<unknown> } })?.response?.data
      const code = resp?.code
      if (code === 'SEAT_ALREADY_TAKEN') {
        setError('선택한 좌석이 이미 예약되었습니다. 다른 좌석을 선택하세요.')
        setSelected(new Set())
        // Refresh seat map
        const { data } = await client.get<ApiResponse<SeatsResponse>>(
          `/api/schedules/${state!.schedule.scheduleId}/seats`,
          { params: { departureStationId: state!.departureStationId, arrivalStationId: state!.arrivalStationId } },
        )
        setCarriages(data.data?.carriages ?? [])
      } else {
        setError(resp?.message ?? '예약 실패')
      }
    } finally {
      setSubmitting(false)
    }
  }

  if (!state?.schedule) return null

  return (
    <div style={styles.page}>
      <div style={styles.container}>
        <h2>좌석 선택</h2>
        <p style={styles.subtitle}>
          {state.schedule.trainType} {state.schedule.trainNumber} |{' '}
          {state.schedule.departureStation.name} → {state.schedule.arrivalStation.name} |{' '}
          {state.schedule.departureTime}
        </p>
        {error && <p style={styles.error}>{error}</p>}
        {loading ? (
          <p>좌석 정보 로딩 중...</p>
        ) : (
          carriages.map(c => (
            <div key={c.carriageId} style={styles.carriage}>
              <strong>{c.carriageNumber}호 칸</strong>
              <div style={styles.seatGrid}>
                {c.seats.map(seat => (
                  <button
                    key={seat.seatId}
                    onClick={() => seat.available && toggleSeat(seat.seatId)}
                    disabled={!seat.available}
                    style={{
                      ...styles.seat,
                      background: !seat.available
                        ? '#e0e0e0'
                        : selected.has(seat.seatId)
                          ? '#1a73e8'
                          : '#c8e6c9',
                      color: selected.has(seat.seatId) ? '#fff' : '#000',
                      cursor: seat.available ? 'pointer' : 'not-allowed',
                    }}
                    title={`${seat.seatNumber} — ${seat.price.toLocaleString()}원`}
                  >
                    {seat.seatNumber}
                  </button>
                ))}
              </div>
            </div>
          ))
        )}
        <div style={styles.footer}>
          <span>선택: {selected.size}석 | 합계: {totalPrice()}원</span>
          <button onClick={handleReserve} disabled={submitting || selected.size === 0} style={styles.reserveBtn}>
            {submitting ? '예약 중...' : '예약하기'}
          </button>
        </div>
      </div>
    </div>
  )

  function totalPrice() {
    let sum = 0
    for (const c of carriages) {
      for (const s of c.seats) {
        if (selected.has(s.seatId)) sum += s.price
      }
    }
    return sum.toLocaleString()
  }
}

const styles = {
  page: { minHeight: '100vh', background: '#f5f5f5', padding: 24 } as React.CSSProperties,
  container: { maxWidth: 700, margin: '0 auto', background: '#fff', padding: 24, borderRadius: 8, boxShadow: '0 2px 8px rgba(0,0,0,.1)' } as React.CSSProperties,
  subtitle: { color: '#555', fontSize: 14, marginBottom: 16 } as React.CSSProperties,
  carriage: { marginBottom: 24 } as React.CSSProperties,
  seatGrid: { display: 'flex', flexWrap: 'wrap' as const, gap: 8, marginTop: 8 },
  seat: { width: 50, height: 36, border: 'none', borderRadius: 4, fontSize: 12, fontWeight: 600 } as React.CSSProperties,
  footer: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 24, borderTop: '1px solid #eee', paddingTop: 16 } as React.CSSProperties,
  reserveBtn: { padding: '10px 24px', background: '#1a73e8', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 15, fontWeight: 600 } as React.CSSProperties,
  error: { color: '#d32f2f', background: '#fce4ec', padding: '8px 12px', borderRadius: 4, fontSize: 13 } as React.CSSProperties,
}
