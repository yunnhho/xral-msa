import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import client from '../api/client'
import { useAuth } from '../contexts/AuthContext'
import type { ApiResponse, Schedule, Station } from '../types/api'

export default function HomePage() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()

  const [stations, setStations] = useState<Station[]>([])
  const [stationError, setStationError] = useState('')
  const [form, setForm] = useState({ departureStationId: '', arrivalStationId: '', date: todayString() })
  const [schedules, setSchedules] = useState<Schedule[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    client
      .get<ApiResponse<Station[]>>('/api/stations')
      .then(({ data }) => setStations(data.data ?? []))
      .catch(() => setStationError('역 목록을 불러오지 못했습니다.'))
  }, [])

  const handleSearch = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    if (form.departureStationId === form.arrivalStationId) {
      setError('출발역과 도착역이 같습니다.')
      return
    }
    setLoading(true)
    try {
      const { data } = await client.get<ApiResponse<{ schedules: Schedule[] }>>('/api/schedules', {
        params: form,
      })
      setSchedules(data.data?.schedules ?? [])
      if ((data.data?.schedules ?? []).length === 0) setError('검색 결과가 없습니다.')
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: ApiResponse<unknown> } })?.response?.data?.message
      setError(msg ?? '검색 실패')
    } finally {
      setLoading(false)
    }
  }

  const handleSelect = (schedule: Schedule) => {
    navigate('/queue', {
      state: {
        schedule,
        departureStationId: form.departureStationId,
        arrivalStationId: form.arrivalStationId,
      },
    })
  }

  return (
    <div style={styles.page}>
      <header style={styles.header}>
        <span style={{ fontSize: 20, fontWeight: 700 }}>XRail</span>
        <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
          <span>{user?.name}</span>
          <button onClick={() => navigate('/reservations')} style={styles.linkBtn}>내 예매</button>
          <button onClick={logout} style={styles.linkBtn}>로그아웃</button>
        </div>
      </header>
      <main style={styles.main}>
        <form onSubmit={handleSearch} style={styles.searchBox}>
          <select
            value={form.departureStationId}
            onChange={e => setForm({ ...form, departureStationId: e.target.value })}
            required
            style={styles.select}
          >
            <option value="">출발역 선택</option>
            {stations.map(s => <option key={s.stationId} value={s.stationId}>{s.name}</option>)}
          </select>
          <select
            value={form.arrivalStationId}
            onChange={e => setForm({ ...form, arrivalStationId: e.target.value })}
            required
            style={styles.select}
          >
            <option value="">도착역 선택</option>
            {stations.map(s => <option key={s.stationId} value={s.stationId}>{s.name}</option>)}
          </select>
          <input
            type="date"
            value={form.date}
            onChange={e => setForm({ ...form, date: e.target.value })}
            required
            style={styles.dateInput}
          />
          <button type="submit" disabled={loading} style={styles.searchBtn}>
            {loading ? '검색 중...' : '열차 검색'}
          </button>
        </form>
        {stationError && <p style={styles.error}>{stationError}</p>}
        {error && <p style={styles.error}>{error}</p>}
        {schedules.length > 0 && (
          <table style={styles.table}>
            <thead>
              <tr>
                <th>열차</th><th>출발</th><th>도착</th><th>소요</th><th>가격</th><th>잔여석</th><th></th>
              </tr>
            </thead>
            <tbody>
              {schedules.map(sc => (
                <tr key={sc.scheduleId}>
                  <td>{sc.trainType} {sc.trainNumber}</td>
                  <td>{sc.departureTime}</td>
                  <td>{sc.arrivalTime}</td>
                  <td>{sc.duration}</td>
                  <td>{sc.estimatedPrice.toLocaleString()}원</td>
                  <td>{sc.availableSeats}</td>
                  <td>
                    <button
                      onClick={() => handleSelect(sc)}
                      disabled={sc.availableSeats === 0}
                      style={sc.availableSeats === 0 ? styles.selectBtnDisabled : styles.selectBtn}
                    >선택</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </main>
    </div>
  )
}

function todayString() {
  return new Date().toISOString().slice(0, 10)
}

const styles = {
  page: { minHeight: '100vh', background: '#f5f5f5' } as React.CSSProperties,
  header: { background: '#1a73e8', color: '#fff', padding: '12px 24px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' } as React.CSSProperties,
  main: { maxWidth: 800, margin: '32px auto', padding: '0 16px' } as React.CSSProperties,
  searchBox: { background: '#fff', padding: 20, borderRadius: 8, boxShadow: '0 2px 8px rgba(0,0,0,.1)', display: 'flex', gap: 10, flexWrap: 'wrap' as const, marginBottom: 20 },
  select: { flex: 1, minWidth: 140, padding: '8px', borderRadius: 4, border: '1px solid #ccc' } as React.CSSProperties,
  dateInput: { flex: 1, minWidth: 140, padding: '8px', borderRadius: 4, border: '1px solid #ccc' } as React.CSSProperties,
  searchBtn: { padding: '8px 20px', background: '#1a73e8', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontWeight: 600 } as React.CSSProperties,
  table: { width: '100%', borderCollapse: 'collapse' as const, background: '#fff', borderRadius: 8, overflow: 'hidden', boxShadow: '0 2px 8px rgba(0,0,0,.1)' },
  selectBtn: { padding: '4px 12px', background: '#1a73e8', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer' } as React.CSSProperties,
  selectBtnDisabled: { padding: '4px 12px', background: '#9e9e9e', color: '#fff', border: 'none', borderRadius: 4, cursor: 'not-allowed' } as React.CSSProperties,
  error: { color: '#d32f2f', fontSize: 13 } as React.CSSProperties,
  linkBtn: { background: 'none', border: 'none', color: '#fff', cursor: 'pointer', fontSize: 14 } as React.CSSProperties,
}
