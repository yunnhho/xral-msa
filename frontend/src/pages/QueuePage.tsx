import { useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { v4 as uuidv4 } from 'uuid'
import client from '../api/client'
import { useQueueStatus } from '../hooks/useQueueStatus'
import type { ApiResponse, QueueStatus, Schedule } from '../types/api'
import { C } from '../styles/theme'
import NavBar from '../components/NavBar'
import BookingSteps from '../components/BookingSteps'

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

  const { status, error, mode, leave } = useQueueStatus('global', joined)

  useEffect(() => { if (!state?.schedule) navigate('/home') }, [state, navigate])

  // ACTIVE 이벤트 수신 → 좌석 선택으로 이동
  useEffect(() => {
    if (status?.status === 'ACTIVE' && status.queueToken) {
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

  // 컴포넌트 마운트 시 자동 대기열 진입
  useEffect(() => {
    if (!state?.schedule) return
    handleJoin()
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const handleJoin = async () => {
    if (joined || joining) return
    setInitError('')
    setJoining(true)
    try {
      const { data } = await client.post<ApiResponse<QueueStatus>>(
        '/api/queue/token', { scope: 'global' },
        { headers: { 'X-Captcha-Token': 'c_stub:0000', 'Idempotency-Key': idempotencyKey.current } },
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

  const handleLeave = async () => { await leave(); navigate('/home') }

  if (!state?.schedule) return null
  const { schedule } = state

  return (
    <div style={{ minHeight: '100vh', background: C.bg }}>
      <NavBar />
      <BookingSteps current={1} />

      <div style={{ maxWidth: 600, margin: '32px auto', padding: '0 20px' }}>
        {/* 열차 요약 카드 */}
        <div style={{ background: '#fff', borderRadius: 10, boxShadow: '0 2px 12px rgba(0,30,87,.08)', padding: '18px 22px', marginBottom: 16 }}>
          <p style={{ fontSize: 11, fontWeight: 700, color: C.textMuted, margin: '0 0 10px', textTransform: 'uppercase', letterSpacing: 0.5 }}>선택한 열차</p>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <span style={{ background: C.accent, color: '#fff', borderRadius: 5, padding: '3px 10px', fontSize: 12, fontWeight: 800 }}>{schedule.trainType}</span>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span style={{ fontSize: 20, fontWeight: 900 }}>{schedule.departureTime.slice(0, 5)}</span>
              <span style={{ fontSize: 12, color: C.textMuted }}>{schedule.departureStation.name}</span>
              <span style={{ color: C.accent, fontWeight: 700 }}>→</span>
              <span style={{ fontSize: 20, fontWeight: 900 }}>{schedule.arrivalTime.slice(0, 5)}</span>
              <span style={{ fontSize: 12, color: C.textMuted }}>{schedule.arrivalStation.name}</span>
            </div>
            <span style={{ marginLeft: 'auto', fontSize: 12, color: C.textMuted }}>{schedule.departureDate}</span>
          </div>
        </div>

        {/* 대기 상태 카드 */}
        <div style={{ background: '#fff', borderRadius: 10, boxShadow: '0 2px 12px rgba(0,30,87,.08)', overflow: 'hidden' }}>
          {/* 에러 상태 */}
          {initError && (
            <div style={{ padding: '32px 28px', textAlign: 'center' }}>
              <div style={{ fontSize: 48, marginBottom: 12 }}>⚠️</div>
              <p style={{ color: C.danger, fontWeight: 700, fontSize: 15, margin: '0 0 8px' }}>대기열 진입에 실패했습니다</p>
              <p style={{ color: C.textSub, fontSize: 13, margin: '0 0 24px' }}>{initError}</p>
              <button onClick={handleJoin} disabled={joining}
                style={{ padding: '12px 28px', background: C.accent, color: '#fff', border: 'none', borderRadius: 8, cursor: 'pointer', fontSize: 14, fontWeight: 700, marginRight: 10 }}>
                다시 시도
              </button>
              <button onClick={() => navigate('/home')}
                style={{ padding: '12px 20px', background: '#fff', color: C.textSub, border: `1px solid ${C.border}`, borderRadius: 8, cursor: 'pointer', fontSize: 14 }}>
                홈으로
              </button>
            </div>
          )}

          {/* 연결/진입 중 */}
          {!initError && (joining || !joined) && (
            <div style={{ padding: '40px 28px', textAlign: 'center' }}>
              <div style={{ width: 52, height: 52, border: `5px solid ${C.accentLight}`, borderTop: `5px solid ${C.accent}`, borderRadius: '50%', margin: '0 auto 20px', animation: 'spin 0.8s linear infinite' }} />
              <p style={{ fontSize: 15, fontWeight: 700, color: C.text, margin: '0 0 6px' }}>예매 대기열에 참가하는 중...</p>
              <p style={{ fontSize: 13, color: C.textSub, margin: 0 }}>잠시만 기다려 주세요.</p>
            </div>
          )}

          {/* 대기 중 */}
          {!initError && joined && (
            <div>
              <div style={{ background: C.accentLight, borderBottom: `1px solid #c7d7f5`, padding: '10px 20px', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <span style={{ width: 8, height: 8, borderRadius: '50%', background: mode === 'sse' ? '#22c55e' : '#f59e0b', display: 'inline-block', animation: 'pulse 1.5s ease-in-out infinite' }} />
                  <span style={{ fontSize: 12, color: C.accent, fontWeight: 600 }}>{mode === 'sse' ? '실시간 연결됨' : '폴링 연결'}</span>
                </div>
                {error && <span style={{ fontSize: 12, color: C.danger }}>{error}</span>}
              </div>

              <div style={{ padding: '36px 28px', textAlign: 'center' }}>
                {!status ? (
                  <>
                    <div style={{ width: 44, height: 44, border: `4px solid ${C.accentLight}`, borderTop: `4px solid ${C.accent}`, borderRadius: '50%', margin: '0 auto 16px', animation: 'spin 0.8s linear infinite' }} />
                    <p style={{ color: C.textSub, fontSize: 14 }}>대기 순번을 확인하는 중...</p>
                  </>
                ) : status.status === 'WAITING' ? (
                  <>
                    <p style={{ fontSize: 11, fontWeight: 700, color: C.textMuted, margin: '0 0 8px', textTransform: 'uppercase', letterSpacing: 0.8 }}>현재 대기 순번</p>
                    <div style={{ fontSize: 84, fontWeight: 900, color: C.accent, lineHeight: 1, letterSpacing: -3 }}>{status.rank ?? '—'}</div>
                    <p style={{ fontSize: 13, color: C.textMuted, margin: '6px 0 24px' }}>번</p>

                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10, marginBottom: 28 }}>
                      {[['전체 대기', `${status.totalWaiting ?? 0}명`], ['예상 대기', `약 ${status.expectedWaitSeconds ?? 0}초`]].map(([label, value]) => (
                        <div key={label} style={{ background: C.bg, borderRadius: 8, padding: '14px 12px' }}>
                          <p style={{ fontSize: 11, color: C.textMuted, margin: '0 0 4px' }}>{label}</p>
                          <p style={{ fontSize: 18, fontWeight: 800, color: C.text, margin: 0 }}>{value}</p>
                        </div>
                      ))}
                    </div>

                    <div style={{ height: 4, background: '#e9ecef', borderRadius: 2, marginBottom: 28, overflow: 'hidden' }}>
                      <div style={{ height: '100%', background: `linear-gradient(90deg, ${C.accent}, #60a5fa)`, borderRadius: 2, animation: 'progress 2.5s ease-in-out infinite', width: '40%' }} />
                    </div>

                    <button onClick={handleLeave}
                      style={{ width: '100%', padding: '12px', background: '#fff', color: C.danger, border: `1.5px solid ${C.danger}`, borderRadius: 8, cursor: 'pointer', fontSize: 14, fontWeight: 700 }}>
                      예매 취소
                    </button>
                  </>
                ) : (
                  <>
                    <div style={{ width: 64, height: 64, background: '#f0fdf4', borderRadius: '50%', margin: '0 auto 16px', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 28 }}>✅</div>
                    <p style={{ fontSize: 16, fontWeight: 800, color: C.success }}>입장이 확인되었습니다!</p>
                    <p style={{ fontSize: 13, color: C.textSub }}>좌석 선택 화면으로 이동합니다...</p>
                  </>
                )}
              </div>
            </div>
          )}
        </div>
      </div>

      <style>{`
        @keyframes spin { to { transform: rotate(360deg) } }
        @keyframes progress { 0% { width: 10%; } 50% { width: 80%; } 100% { width: 10%; } }
        @keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.4; } }
      `}</style>
    </div>
  )
}
