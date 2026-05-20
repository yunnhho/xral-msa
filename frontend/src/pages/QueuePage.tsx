import { useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { v4 as uuidv4 } from 'uuid'
import client from '../api/client'
import { useQueueStatus } from '../hooks/useQueueStatus'
import type { ApiResponse, QueueStatus, Schedule } from '../types/api'

interface LocationState {
  schedule: Schedule
  departureStationId: string
  arrivalStationId: string
}

export default function QueuePage() {
  const navigate = useNavigate()
  const location = useLocation()
  const state = location.state as LocationState | null

  const [joined, setJoined] = useState(false)
  const [joining, setJoining] = useState(false)
  const [initError, setInitError] = useState('')
  const idempotencyKey = useRef(uuidv4())
  const queueTokenRef = useRef<string | null>(null)

  const { status, error, mode, leave } = useQueueStatus('global', joined)

  // Redirect if no schedule in state
  useEffect(() => {
    if (!state?.schedule) navigate('/home')
  }, [state, navigate])

  // When ACTIVE received, save token and go to seats
  useEffect(() => {
    if (status?.status === 'ACTIVE' && status.queueToken) {
      queueTokenRef.current = status.queueToken
      navigate('/seats', {
        state: {
          schedule: state!.schedule,
          departureStationId: state!.departureStationId,
          arrivalStationId: state!.arrivalStationId,
          queueToken: status.queueToken,
        },
      })
    }
  }, [status, navigate, state])

  const handleJoin = async () => {
    setInitError('')
    setJoining(true)
    try {
      const { data } = await client.post<ApiResponse<QueueStatus>>(
        '/api/queue/token',
        { scope: 'global' },
        {
          headers: {
            'X-Captcha-Token': 'c_stub:0000',
            'Idempotency-Key': idempotencyKey.current,
          },
        },
      )
      if (data.data?.status === 'ACTIVE' && data.data.queueToken) {
        navigate('/seats', {
          state: {
            schedule: state!.schedule,
            departureStationId: state!.departureStationId,
            arrivalStationId: state!.arrivalStationId,
            queueToken: data.data.queueToken,
          },
        })
        return
      }
      setJoined(true)
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: ApiResponse<unknown> } })?.response?.data?.message
      setInitError(msg ?? '대기열 진입 실패')
    } finally {
      setJoining(false)
    }
  }

  const handleLeave = async () => {
    await leave()
    navigate('/home')
  }

  if (!state?.schedule) return null

  const { schedule } = state

  return (
    <div style={styles.page}>
      <div style={styles.card}>
        <h2>대기열</h2>
        <div style={styles.scheduleInfo}>
          <strong>{schedule.trainType} {schedule.trainNumber}</strong> |{' '}
          {schedule.departureStation.name} → {schedule.arrivalStation.name}<br />
          {schedule.departureDate} {schedule.departureTime}
        </div>
        {!joined ? (
          <>
            <p>이 열차는 혼잡 시 대기열을 거칩니다. 대기열에 참가하세요.</p>
            {initError && <p style={styles.error}>{initError}</p>}
            <button onClick={handleJoin} disabled={joining} style={styles.btn}>
              {joining ? '참가 중...' : '대기열 참가'}
            </button>
            <button onClick={() => navigate('/home')} style={styles.cancelBtn}>취소</button>
          </>
        ) : (
          <>
            <p style={styles.modeTag}>연결 방식: {mode}</p>
            {error && <p style={styles.error}>{error}</p>}
            {status ? (
              <div style={styles.statusBox}>
                {status.status === 'WAITING' ? (
                  <>
                    <div style={styles.rank}>{status.rank ?? '-'}</div>
                    <div>대기 순번</div>
                    <div style={{ marginTop: 8 }}>전체 대기: {status.totalWaiting}명</div>
                    <div>예상 대기: 약 {status.expectedWaitSeconds}초</div>
                  </>
                ) : (
                  <div>입장 처리 중...</div>
                )}
              </div>
            ) : (
              <div style={styles.statusBox}>연결 중...</div>
            )}
            <button onClick={handleLeave} style={styles.cancelBtn}>대기열 이탈</button>
          </>
        )}
      </div>
    </div>
  )
}

const styles = {
  page: { display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', background: '#f5f5f5' } as React.CSSProperties,
  card: { background: '#fff', padding: 32, borderRadius: 8, boxShadow: '0 2px 8px rgba(0,0,0,.12)', width: 400, textAlign: 'center' as const },
  scheduleInfo: { background: '#f0f4ff', padding: 12, borderRadius: 6, marginBottom: 16, fontSize: 14 } as React.CSSProperties,
  statusBox: { background: '#f0f4ff', padding: 24, borderRadius: 8, margin: '16px 0' } as React.CSSProperties,
  rank: { fontSize: 48, fontWeight: 700, color: '#1a73e8' } as React.CSSProperties,
  btn: { display: 'block', width: '100%', padding: '10px', background: '#1a73e8', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 15, marginTop: 12 } as React.CSSProperties,
  cancelBtn: { display: 'block', width: '100%', padding: '10px', background: '#fff', color: '#666', border: '1px solid #ccc', borderRadius: 4, cursor: 'pointer', fontSize: 14, marginTop: 8 } as React.CSSProperties,
  error: { color: '#d32f2f', fontSize: 13 } as React.CSSProperties,
  modeTag: { fontSize: 11, color: '#999' } as React.CSSProperties,
}
