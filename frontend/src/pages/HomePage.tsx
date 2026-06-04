import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import client from '../api/client'
import type { ApiResponse, Schedule, Station } from '../types/api'
import { C, TRAIN_BADGE } from '../styles/theme'
import NavBar from '../components/NavBar'

export default function HomePage() {
  const navigate = useNavigate()
  const [stations, setStations] = useState<Station[]>([])
  const [form, setForm] = useState({ departureStationId: '', arrivalStationId: '', date: todayStr() })
  const [schedules, setSchedules] = useState<Schedule[]>([])
  const [searched, setSearched] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    client.get<ApiResponse<Station[]>>('/api/stations')
      .then(({ data }) => setStations(data.data ?? []))
      .catch(() => setError('역 목록을 불러오지 못했습니다.'))
  }, [])

  const swap = () => setForm(f => ({ ...f, departureStationId: f.arrivalStationId, arrivalStationId: f.departureStationId }))

  // 상호 필터
  const deptOpts = stations.filter(s => String(s.stationId) !== form.arrivalStationId)
  const arrvOpts = stations.filter(s => String(s.stationId) !== form.departureStationId)

  const handleSearch = async (e: React.FormEvent) => {
    e.preventDefault()
    if (form.departureStationId === form.arrivalStationId && form.departureStationId) {
      setError('출발역과 도착역이 같습니다.'); return
    }
    setError(''); setLoading(true); setSearched(true); setSchedules([])
    try {
      const { data } = await client.get<ApiResponse<{ schedules: Schedule[] }>>('/api/schedules', { params: form })
      const list = data.data?.schedules ?? []
      setSchedules(list)
      if (list.length === 0) setError('조회된 열차가 없습니다.')
    } catch (err: unknown) {
      setError((err as { response?: { data?: ApiResponse<unknown> } })?.response?.data?.message ?? '검색 실패')
    } finally { setLoading(false) }
  }

  const deptName = stations.find(s => String(s.stationId) === form.departureStationId)?.name ?? ''
  const arrvName = stations.find(s => String(s.stationId) === form.arrivalStationId)?.name ?? ''

  const sel: React.CSSProperties = {
    width: '100%', padding: '10px 12px', border: `1px solid ${C.border}`,
    borderRadius: 4, fontSize: 15, fontWeight: 600, color: C.text,
    background: C.surface, boxSizing: 'border-box', appearance: 'none',
    backgroundImage: `url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='6'%3E%3Cpath d='M0 0l5 6 5-6z' fill='%2394a3b8'/%3E%3C/svg%3E")`,
    backgroundRepeat: 'no-repeat', backgroundPosition: 'right 10px center', paddingRight: 30,
  }

  return (
    <div style={{ minHeight: '100vh', background: C.bg }}>
      <NavBar />

      {/* 검색 히어로 */}
      <div style={{ background: C.brand, padding: '32px 24px 0' }}>
        <div style={{ maxWidth: 860, margin: '0 auto' }}>
          <p style={{ color: 'rgba(255,255,255,.45)', fontSize: 11, margin: '0 0 4px', letterSpacing: 1.5, textTransform: 'uppercase', fontWeight: 600 }}>승차권 예매</p>
          <h1 style={{ color: '#fff', margin: '0 0 22px', fontSize: 24, fontWeight: 800, letterSpacing: -0.5 }}>어디로 가시나요?</h1>

          <form onSubmit={handleSearch}>
            <div style={{ background: C.surface, borderRadius: '6px 6px 0 0', padding: '20px 22px', display: 'flex', gap: 10, alignItems: 'flex-end', flexWrap: 'wrap' }}>
              {/* 출발역 */}
              <div style={{ flex: '1 1 140px' }}>
                <label style={{ display: 'block', fontSize: 10, fontWeight: 700, color: C.textMuted, marginBottom: 5, letterSpacing: 1, textTransform: 'uppercase' }}>출발역</label>
                <select value={form.departureStationId} onChange={e => setForm({ ...form, departureStationId: e.target.value })} required style={sel}>
                  <option value="">선택</option>
                  {deptOpts.map(s => <option key={s.stationId} value={s.stationId}>{s.name}</option>)}
                </select>
              </div>

              {/* 스왑 */}
              <button type="button" onClick={swap}
                style={{ width: 36, height: 36, borderRadius: '50%', border: `1px solid ${C.border}`, background: C.surface, cursor: 'pointer', color: C.textSub, fontSize: 15, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0, marginBottom: 1 }}>
                ⇄
              </button>

              {/* 도착역 */}
              <div style={{ flex: '1 1 140px' }}>
                <label style={{ display: 'block', fontSize: 10, fontWeight: 700, color: C.textMuted, marginBottom: 5, letterSpacing: 1, textTransform: 'uppercase' }}>도착역</label>
                <select value={form.arrivalStationId} onChange={e => setForm({ ...form, arrivalStationId: e.target.value })} required style={sel}>
                  <option value="">선택</option>
                  {arrvOpts.map(s => <option key={s.stationId} value={s.stationId}>{s.name}</option>)}
                </select>
              </div>

              {/* 날짜 */}
              <div style={{ flex: '1 1 130px' }}>
                <label style={{ display: 'block', fontSize: 10, fontWeight: 700, color: C.textMuted, marginBottom: 5, letterSpacing: 1, textTransform: 'uppercase' }}>출발일</label>
                <input type="date" value={form.date} onChange={e => setForm({ ...form, date: e.target.value })} required
                  min={todayStr()} max={maxBookingDateStr()}
                  style={{ ...sel, fontWeight: 500, fontSize: 14 }} />
              </div>

              {/* 조회 버튼 */}
              <button type="submit" disabled={loading}
                style={{ padding: '10px 24px', height: 38, background: loading ? C.textMuted : C.cta, color: '#fff', border: 'none', borderRadius: 4, cursor: loading ? 'not-allowed' : 'pointer', fontSize: 13, fontWeight: 700, flexShrink: 0, letterSpacing: 0.3 }}>
                {loading ? '조회 중' : '열차 조회'}
              </button>
            </div>
          </form>
        </div>
      </div>

      {/* 결과 */}
      <div style={{ maxWidth: 860, margin: '0 auto', padding: '0 24px 48px' }}>
        {error && (
          <div style={{ background: C.surface, borderLeft: `1px solid ${C.border}`, borderRight: `1px solid ${C.border}`, borderBottom: `1px solid ${C.border}`, borderRadius: '0 0 6px 6px', padding: '16px 20px' }}>
            <p style={{ color: C.danger, fontSize: 14, margin: 0 }}>{error}</p>
          </div>
        )}

        {searched && !error && schedules.length > 0 && (
          <div className="fade-in">
            {/* 결과 헤더 */}
            <div style={{ padding: '14px 0 10px', display: 'flex', alignItems: 'center', gap: 8, borderBottom: `1px solid ${C.border}`, marginBottom: 0 }}>
              <span style={{ fontWeight: 800, fontSize: 16, color: C.text }}>{deptName}</span>
              <span style={{ color: C.accent, fontWeight: 700, fontSize: 18, lineHeight: 1 }}>→</span>
              <span style={{ fontWeight: 800, fontSize: 16, color: C.text }}>{arrvName}</span>
              <span style={{ fontSize: 13, color: C.textSub, marginLeft: 4 }}>{form.date.replace(/-/g, '.')}</span>
              <span style={{ marginLeft: 'auto', fontSize: 12, color: C.textMuted }}>{schedules.length}편</span>
            </div>

            {/* 열차 목록 */}
            <div style={{ background: C.surface, border: `1px solid ${C.border}`, borderTop: 'none', borderRadius: '0 0 6px 6px', overflow: 'hidden' }}>
              {/* 컬럼 헤더 */}
              <div style={{ display: 'grid', gridTemplateColumns: '72px 1fr 70px 120px 70px 100px', gap: 8, padding: '8px 20px', background: C.bg, borderBottom: `1px solid ${C.border}`, fontSize: 10, fontWeight: 700, color: C.textMuted, textTransform: 'uppercase', letterSpacing: 0.5 }}>
                <span>열차</span><span>출발 · 도착</span><span style={{ textAlign: 'center' }}>소요</span><span style={{ textAlign: 'right' }}>운임</span><span style={{ textAlign: 'center' }}>잔여</span><span />
              </div>

              {schedules.map((sc, idx) => {
                const tooLate = isTooLate(sc)
                const available = sc.availableSeats > 0
                const bookable = available && !tooLate
                const badgeColor = TRAIN_BADGE[sc.trainType] ?? C.accent
                return (
                  <div key={sc.scheduleId}
                    style={{ display: 'grid', gridTemplateColumns: '72px 1fr 70px 120px 70px 100px', gap: 8, padding: '14px 20px', borderBottom: idx < schedules.length - 1 ? `1px solid ${C.border}` : 'none', alignItems: 'center', opacity: bookable ? 1 : 0.55 }}>
                    <div>
                      <span style={{ background: badgeColor, color: '#fff', padding: '2px 7px', borderRadius: 3, fontSize: 11, fontWeight: 800 }}>{sc.trainType}</span>
                      <div style={{ fontSize: 10, color: C.textMuted, marginTop: 3 }}>{sc.trainNumber}</div>
                    </div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                      <div>
                        <div style={{ fontSize: 22, fontWeight: 900, letterSpacing: -0.5, lineHeight: 1 }}>{sc.departureTime.slice(0, 5)}</div>
                        <div style={{ fontSize: 10, color: C.textMuted }}>{sc.departureStation.name}</div>
                      </div>
                      <div style={{ height: 1, flex: 1, background: C.border, position: 'relative' }}>
                        <div style={{ position: 'absolute', right: -4, top: -5, color: C.textMuted, fontSize: 9 }}>▶</div>
                      </div>
                      <div>
                        <div style={{ fontSize: 22, fontWeight: 900, letterSpacing: -0.5, lineHeight: 1 }}>{sc.arrivalTime.slice(0, 5)}</div>
                        <div style={{ fontSize: 10, color: C.textMuted }}>{sc.arrivalStation.name}</div>
                      </div>
                    </div>
                    <div style={{ textAlign: 'center', fontSize: 12, color: C.textSub }}>{sc.duration}</div>
                    <div style={{ textAlign: 'right' }}>
                      <div style={{ fontSize: 17, fontWeight: 800, letterSpacing: -0.3 }}>{sc.estimatedPrice.toLocaleString()}<span style={{ fontSize: 11, fontWeight: 400, color: C.textSub }}>원</span></div>
                    </div>
                    <div style={{ textAlign: 'center' }}>
                      <span style={{ fontSize: 13, fontWeight: 700, color: tooLate ? C.textMuted : !available ? C.danger : sc.availableSeats < 10 ? C.warning : C.success }}>
                        {tooLate ? '마감' : !available ? '매진' : `${sc.availableSeats}석`}
                      </span>
                    </div>
                    <div style={{ textAlign: 'right' }}>
                      <button
                        onClick={() => navigate('/queue', { state: { schedule: sc, departureStationId: form.departureStationId, arrivalStationId: form.arrivalStationId } })}
                        disabled={!bookable}
                        title={tooLate ? '출발이 임박하여 예매할 수 없습니다.' : undefined}
                        style={{ padding: '7px 16px', background: !bookable ? C.bgAlt : C.accent, color: !bookable ? C.textMuted : '#fff', border: 'none', borderRadius: 4, cursor: !bookable ? 'not-allowed' : 'pointer', fontSize: 12, fontWeight: 700, letterSpacing: 0.2 }}>
                        {tooLate ? '마감' : !available ? '매진' : '예매'}
                      </button>
                    </div>
                  </div>
                )
              })}
            </div>
          </div>
        )}

        {!searched && (
          <div style={{ background: C.surface, borderRadius: '0 0 6px 6px', padding: '48px 24px', textAlign: 'center', border: `1px solid ${C.border}`, borderTop: 'none' }}>
            <p style={{ color: C.textMuted, fontSize: 14, margin: 0 }}>출발역, 도착역, 날짜를 선택하고 열차를 조회하세요.</p>
          </div>
        )}
      </div>
    </div>
  )
}

// 백엔드 RESERVATION_CLOSE_BEFORE_MINUTES(10분)와 일치 — 출발 임박 열차는 예매 마감
const CLOSE_BEFORE_MINUTES = 10
// 예매 가능 범위: 오늘 ~ +30일 (백엔드 BOOKING_WINDOW_DAYS와 일치)
const BOOKING_WINDOW_DAYS = 30

function todayStr() { return localDateStr(new Date()) }

function maxBookingDateStr() {
  const d = new Date()
  d.setDate(d.getDate() + BOOKING_WINDOW_DAYS)
  return localDateStr(d)
}

function localDateStr(d: Date) {
  // 로컬 타임존 기준 YYYY-MM-DD (toISOString의 UTC 변환으로 날짜가 어긋나는 것 방지)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function isTooLate(sc: Schedule) {
  const departure = new Date(`${sc.departureDate}T${sc.departureTime}`)
  return departure.getTime() - Date.now() < CLOSE_BEFORE_MINUTES * 60 * 1000
}
