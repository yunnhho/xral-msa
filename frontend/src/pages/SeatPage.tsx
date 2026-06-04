import { useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { v4 as uuidv4 } from 'uuid'
import client from '../api/client'
import type { ApiResponse, CarriageInfo, Reservation, Schedule, SeatsResponse } from '../types/api'
import { C } from '../styles/theme'
import NavBar from '../components/NavBar'
import BookingSteps from '../components/BookingSteps'

const MAX_SEATS = 10

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
  const [activeCarriage, setActiveCarriage] = useState(0)
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')
  const idempotencyKey = useRef(uuidv4())

  const loadSeats = (signal?: AbortSignal) => {
    if (!state?.schedule) return
    const { schedule, departureStationId, arrivalStationId } = state
    setLoading(true)
    client
      .get<ApiResponse<SeatsResponse>>(`/api/schedules/${schedule.scheduleId}/seats`, {
        params: { departureStationId, arrivalStationId }, signal,
        headers: { 'X-Queue-Token': state.queueToken },
      })
      .then(({ data }) => { setCarriages(data.data?.carriages ?? []); setLoading(false) })
      .catch(err => {
        if (!signal?.aborted) {
          const msg = (err as { response?: { data?: ApiResponse<unknown> } })?.response?.data?.message ?? '좌석 정보를 불러오지 못했습니다.'
          setError(msg)
          setLoading(false)
        }
      })
  }

  useEffect(() => {
    if (!state?.schedule) { navigate('/home'); return }
    const ctrl = new AbortController()
    loadSeats(ctrl.signal)
    return () => ctrl.abort()
  }, [state, navigate])

  const toggleSeat = (seatId: number) => {
    if (error) setError('')
    setSelected(prev => {
      const next = new Set(prev)
      if (next.has(seatId)) {
        next.delete(seatId)
      } else {
        if (next.size >= MAX_SEATS) {
          setError(`최대 ${MAX_SEATS}석까지 선택할 수 있습니다.`)
          return prev
        }
        next.add(seatId)
      }
      return next
    })
  }

  const handleReserve = async () => {
    if (selected.size === 0) { setError('좌석을 1석 이상 선택해주세요.'); return }
    setError(''); setSubmitting(true)
    try {
      const { data } = await client.post<ApiResponse<Reservation>>(
        '/api/reservations',
        { scheduleId: state!.schedule.scheduleId, departureStationId: Number(state!.departureStationId), arrivalStationId: Number(state!.arrivalStationId), seatIds: Array.from(selected) },
        { headers: { 'X-Queue-Token': state!.queueToken, 'Idempotency-Key': idempotencyKey.current } },
      )
      if (data.data) navigate('/payment', { state: { reservation: data.data } })
    } catch (err: unknown) {
      const resp = (err as { response?: { data?: ApiResponse<unknown> } })?.response?.data
      if (resp?.code === 'SEAT_ALREADY_TAKEN') {
        setError('선택한 좌석이 이미 예약되었습니다. 다른 좌석을 선택해주세요.')
        setSelected(new Set())
        loadSeats()
      } else {
        setError(resp?.message ?? '예약 실패')
      }
    } finally { setSubmitting(false) }
  }

  if (!state?.schedule) return null
  const { schedule } = state
  const carriage = carriages[activeCarriage]
  const allSeats = carriages.flatMap(c => c.seats)
  const totalPrice = allSeats.filter(s => selected.has(s.seatId)).reduce((sum, s) => sum + s.price, 0)
  const canBook = selected.size > 0 && !submitting

  return (
    <div style={{ minHeight: '100vh', background: C.bg, display: 'flex', flexDirection: 'column' }}>
      <NavBar />
      <BookingSteps current={2} />

      {/* 서브헤더 */}
      <div style={{ background: '#fff', borderBottom: `1px solid ${C.border}` }}>
        <div style={{ maxWidth: 920, margin: '0 auto', padding: '11px 20px', display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <span style={{ background: C.accent, color: '#fff', borderRadius: 4, padding: '3px 9px', fontSize: 11, fontWeight: 800 }}>{schedule.trainType}</span>
          <span style={{ fontWeight: 800 }}>{schedule.departureTime.slice(0, 5)}</span>
          <span style={{ fontSize: 12, color: C.textMuted }}>{schedule.departureStation.name}</span>
          <span style={{ color: C.accent, fontWeight: 700 }}>→</span>
          <span style={{ fontWeight: 800 }}>{schedule.arrivalTime.slice(0, 5)}</span>
          <span style={{ fontSize: 12, color: C.textMuted }}>{schedule.arrivalStation.name}</span>
          <span style={{ color: C.border, margin: '0 2px' }}>|</span>
          <span style={{ fontSize: 12, color: C.textSub }}>{schedule.departureDate}</span>
          <span style={{ marginLeft: 'auto', fontSize: 12, color: C.textMuted }}>최대 {MAX_SEATS}석 선택 가능</span>
        </div>
      </div>

      <div style={{ flex: 1, maxWidth: 920, margin: '0 auto', width: '100%', padding: '16px 20px 100px', display: 'flex', gap: 20 }}>
        {/* 좌측: 좌석 맵 */}
        <div style={{ flex: 1 }}>
          {error && (
            <div style={{ background: C.dangerBg, border: `1px solid #fca5a5`, color: C.danger, borderRadius: 8, padding: '11px 14px', marginBottom: 12, fontSize: 13 }}>
              ⚠ {error}
            </div>
          )}

          {loading ? (
            <div style={{ background: '#fff', borderRadius: 10, padding: '60px', textAlign: 'center', boxShadow: '0 2px 12px rgba(0,30,87,.08)' }}>
              <div style={{ width: 40, height: 40, border: `4px solid ${C.accentLight}`, borderTop: `4px solid ${C.accent}`, borderRadius: '50%', margin: '0 auto 12px', animation: 'spin 0.8s linear infinite' }} />
              <p style={{ color: C.textSub, fontSize: 14, margin: 0 }}>좌석 정보를 불러오는 중...</p>
            </div>
          ) : (
            <div style={{ background: '#fff', borderRadius: 10, boxShadow: '0 2px 12px rgba(0,30,87,.08)', overflow: 'hidden' }}>
              {/* 호차 탭 */}
              <div style={{ display: 'flex', borderBottom: `1px solid ${C.border}`, overflowX: 'auto' }}>
                {carriages.map((c, idx) => {
                  const cnt = c.seats.filter(s => selected.has(s.seatId)).length
                  return (
                    <button key={c.carriageId} onClick={() => setActiveCarriage(idx)}
                      style={{ padding: '11px 20px', border: 'none', borderBottom: `3px solid ${idx === activeCarriage ? C.accent : 'transparent'}`, background: 'none', cursor: 'pointer', fontSize: 13, fontWeight: idx === activeCarriage ? 800 : 400, color: idx === activeCarriage ? C.accent : C.textSub, whiteSpace: 'nowrap', position: 'relative', flexShrink: 0 }}>
                      {c.carriageNumber}호차
                      {cnt > 0 && <span style={{ position: 'absolute', top: 5, right: 4, width: 15, height: 15, background: C.accent, color: '#fff', borderRadius: '50%', fontSize: 9, fontWeight: 800, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>{cnt}</span>}
                    </button>
                  )
                })}
              </div>

              {carriage && (
                <div style={{ padding: '18px 22px' }}>
                  {/* 범례 */}
                  <div style={{ display: 'flex', gap: 14, marginBottom: 18, fontSize: 11, color: C.textSub, flexWrap: 'wrap' }}>
                    {[
                      { bg: '#dbeafe', border: '#93c5fd', label: '선택 가능' },
                      { bg: C.accent, border: C.accent, label: '선택됨' },
                      { bg: '#e5e7eb', border: '#d1d5db', label: '예약됨' },
                    ].map(({ bg, border, label }) => (
                      <div key={label} style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
                        <div style={{ width: 14, height: 14, background: bg, border: `1.5px solid ${border}`, borderRadius: 3 }} />
                        {label}
                      </div>
                    ))}
                  </div>

                  {/* 좌석 그리드 - A B | C D */}
                  <div style={{ maxWidth: 400, margin: '0 auto' }}>
                    <div style={{ display: 'grid', gridTemplateColumns: '32px 1fr 1fr 10px 1fr 1fr', gap: 5, marginBottom: 6 }}>
                      <div />
                      {['A', 'B'].map(l => <div key={l} style={{ fontSize: 10, fontWeight: 700, color: C.textMuted, textAlign: 'center' }}>{l}</div>)}
                      <div />
                      {['C', 'D'].map(l => <div key={l} style={{ fontSize: 10, fontWeight: 700, color: C.textMuted, textAlign: 'center' }}>{l}</div>)}
                    </div>

                    {groupByRow(carriage.seats).map(({ row, seats }) => (
                      <div key={row} style={{ display: 'grid', gridTemplateColumns: '32px 1fr 1fr 10px 1fr 1fr', gap: 5, marginBottom: 5 }}>
                        <div style={{ fontSize: 11, fontWeight: 700, color: C.textMuted, display: 'flex', alignItems: 'center', justifyContent: 'flex-end', paddingRight: 3 }}>{row}</div>
                        {['A', 'B'].map(col => {
                          const seat = seats.find(s => s.seatNumber.endsWith(col))
                          if (!seat) return <div key={col} />
                          const isSel = selected.has(seat.seatId)
                          return (
                            <button key={seat.seatId}
                              onClick={() => seat.available && toggleSeat(seat.seatId)}
                              disabled={!seat.available}
                              title={`${seat.seatNumber} — ${seat.price.toLocaleString()}원`}
                              style={{ height: 36, borderRadius: 4, border: isSel ? `2px solid ${C.accent}` : seat.available ? '1.5px solid #93c5fd' : '1px solid #d1d5db', background: isSel ? C.accent : seat.available ? '#dbeafe' : '#e5e7eb', color: isSel ? '#fff' : seat.available ? C.accent : '#9ca3af', cursor: seat.available ? 'pointer' : 'not-allowed', fontSize: 10, fontWeight: 700, transition: 'all 0.1s' }}>
                              {seat.seatNumber}
                            </button>
                          )
                        })}
                        <div style={{ borderLeft: '2px dashed #d1d5db', margin: '4px 0' }} />
                        {['C', 'D'].map(col => {
                          const seat = seats.find(s => s.seatNumber.endsWith(col))
                          if (!seat) return <div key={col} />
                          const isSel = selected.has(seat.seatId)
                          return (
                            <button key={seat.seatId}
                              onClick={() => seat.available && toggleSeat(seat.seatId)}
                              disabled={!seat.available}
                              title={`${seat.seatNumber} — ${seat.price.toLocaleString()}원`}
                              style={{ height: 36, borderRadius: 4, border: isSel ? `2px solid ${C.accent}` : seat.available ? '1.5px solid #93c5fd' : '1px solid #d1d5db', background: isSel ? C.accent : seat.available ? '#dbeafe' : '#e5e7eb', color: isSel ? '#fff' : seat.available ? C.accent : '#9ca3af', cursor: seat.available ? 'pointer' : 'not-allowed', fontSize: 10, fontWeight: 700, transition: 'all 0.1s' }}>
                              {seat.seatNumber}
                            </button>
                          )
                        })}
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>

        {/* 우측: 선택 요약 (데스크탑만) */}
        <div style={{ width: 210, flexShrink: 0 }}>
          <div style={{ background: '#fff', borderRadius: 10, boxShadow: '0 2px 12px rgba(0,30,87,.08)', padding: '18px', position: 'sticky', top: 20 }}>
            <h4 style={{ margin: '0 0 14px', fontSize: 13, fontWeight: 800, color: C.text }}>선택 현황</h4>

            <div style={{ marginBottom: 14, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <span style={{ fontSize: 12, color: C.textSub }}>선택 좌석</span>
              <span style={{ fontWeight: 800, color: C.accent, fontSize: 15 }}>{selected.size}<span style={{ fontSize: 11, fontWeight: 400, color: C.textMuted }}>석</span> <span style={{ fontSize: 11, color: C.textMuted }}>(최대 {MAX_SEATS})</span></span>
            </div>

            {selected.size > 0 && (
              <>
                <div style={{ marginBottom: 12 }}>
                  {allSeats.filter(s => selected.has(s.seatId)).map(s => (
                    <div key={s.seatId} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '5px 7px', background: C.accentLight, borderRadius: 4, marginBottom: 3 }}>
                      <span style={{ fontSize: 12, fontWeight: 800, color: C.accent }}>{s.seatNumber}</span>
                      <span style={{ fontSize: 11, color: C.textSub }}>{s.price.toLocaleString()}원</span>
                    </div>
                  ))}
                </div>
                <div style={{ borderTop: `1px solid ${C.border}`, paddingTop: 10, marginBottom: 14 }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <span style={{ fontSize: 12, color: C.textSub }}>합계</span>
                    <span style={{ fontSize: 18, fontWeight: 900, color: C.accent }}>{totalPrice.toLocaleString()}<span style={{ fontSize: 10 }}>원</span></span>
                  </div>
                </div>
              </>
            )}

            <button onClick={handleReserve} disabled={!canBook}
              style={{ width: '100%', padding: '12px', background: !canBook ? '#d1d5db' : C.cta, color: !canBook ? C.textMuted : '#fff', border: 'none', borderRadius: 5, cursor: !canBook ? 'not-allowed' : 'pointer', fontSize: 13, fontWeight: 800, transition: 'background 0.2s' }}>
              {submitting ? '예약 중...' : selected.size === 0 ? '좌석을 선택하세요' : `${selected.size}석 예약하기`}
            </button>
          </div>
        </div>
      </div>

      {/* 하단 고정 바 (모바일/좁은 화면 전용 — 우측 패널 숨겨질 때 표시) */}
      <div style={{ position: 'fixed', bottom: 0, left: 0, right: 0, background: '#fff', borderTop: `1px solid ${C.border}`, padding: '12px 20px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', boxShadow: '0 -2px 12px rgba(0,30,87,.08)', zIndex: 50 }}>
        <div>
          {selected.size === 0
            ? <span style={{ fontSize: 13, color: C.textMuted }}>좌석을 선택하세요 (최대 {MAX_SEATS}석)</span>
            : <span><span style={{ fontSize: 17, fontWeight: 900, color: C.text }}>{totalPrice.toLocaleString()}원</span> <span style={{ fontSize: 12, color: C.textMuted }}>/ {selected.size}석</span></span>
          }
        </div>
        <button onClick={handleReserve} disabled={!canBook}
          style={{ padding: '10px 24px', background: !canBook ? '#e5e7eb' : C.cta, color: !canBook ? C.textMuted : '#fff', border: 'none', borderRadius: 5, cursor: !canBook ? 'not-allowed' : 'pointer', fontSize: 13, fontWeight: 800 }}>
          {submitting ? '예약 중...' : selected.size === 0 ? '좌석 선택 필요' : `${selected.size}석 예약`}
        </button>
      </div>

    </div>
  )
}

function groupByRow(seats: { seatId: number; seatNumber: string; available: boolean; price: number }[]) {
  const map = new Map<string, typeof seats>()
  for (const seat of seats) {
    const row = seat.seatNumber.replace(/[A-Z]/g, '')
    if (!map.has(row)) map.set(row, [])
    map.get(row)!.push(seat)
  }
  return Array.from(map.entries())
    .sort(([a], [b]) => Number(a) - Number(b))
    .map(([row, s]) => ({ row, seats: s }))
}
