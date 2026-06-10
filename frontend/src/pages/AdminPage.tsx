import { useCallback, useEffect, useState } from 'react'
import client from '../api/client'
import type {
  ApiResponse, DltAlert, NotificationStats, OutboxStatus,
  Page, PaymentStats, Reservation, ReservationStats, SagaLog,
} from '../types/api'
import { C } from '../styles/theme'
import NavBar from '../components/NavBar'

type Tab = 'dashboard' | 'reservations' | 'queue' | 'saga' | 'dlt'

const TABS: { key: Tab; label: string }[] = [
  { key: 'dashboard', label: '대시보드' },
  { key: 'reservations', label: '예약 관리' },
  { key: 'queue', label: '대기열 제어' },
  { key: 'saga', label: '사가 로그' },
  { key: 'dlt', label: 'DLT 알림' },
]

const won = (n: number) => `${n.toLocaleString()}원`

export default function AdminPage() {
  const [tab, setTab] = useState<Tab>('dashboard')
  return (
    <div style={{ minHeight: '100vh', background: C.bg }}>
      <NavBar />
      <div style={{ background: C.surface, borderBottom: `1px solid ${C.border}` }}>
        <div style={{ maxWidth: 1000, margin: '0 auto', padding: '18px 24px' }}>
          <h2 style={{ margin: 0, fontSize: 20, fontWeight: 800, color: C.text }}>운영자 콘솔</h2>
          <p style={{ margin: '3px 0 0', fontSize: 13, color: C.textMuted }}>예약·결제·알림 모니터링 및 운영 액션</p>
        </div>
        <div style={{ maxWidth: 1000, margin: '0 auto', padding: '0 24px', display: 'flex', gap: 4 }}>
          {TABS.map(t => (
            <button key={t.key} onClick={() => setTab(t.key)}
              style={{
                background: 'none', border: 'none', cursor: 'pointer', fontSize: 13, fontWeight: 700,
                padding: '10px 14px', color: tab === t.key ? C.accent : C.textMuted,
                borderBottom: `2px solid ${tab === t.key ? C.accent : 'transparent'}`,
              }}>
              {t.label}
            </button>
          ))}
        </div>
      </div>

      <div style={{ maxWidth: 1000, margin: '0 auto', padding: '20px 24px 48px' }}>
        {tab === 'dashboard' && <Dashboard />}
        {tab === 'reservations' && <Reservations />}
        {tab === 'queue' && <QueueControl />}
        {tab === 'saga' && <SagaLogs />}
        {tab === 'dlt' && <DltAlerts />}
      </div>
    </div>
  )
}

// ===== 대시보드 =====
function Dashboard() {
  const [resv, setResv] = useState<ReservationStats | null>(null)
  const [pay, setPay] = useState<PaymentStats | null>(null)
  const [notif, setNotif] = useState<NotificationStats | null>(null)
  const [outbox, setOutbox] = useState<OutboxStatus | null>(null)
  const [trainOutbox, setTrainOutbox] = useState<OutboxStatus | null>(null)
  const [err, setErr] = useState('')

  const load = useCallback(() => {
    setErr('')
    Promise.allSettled([
      client.get<ApiResponse<ReservationStats>>('/api/admin/reservations/stats'),
      client.get<ApiResponse<PaymentStats>>('/api/admin/payments/stats'),
      client.get<ApiResponse<NotificationStats>>('/api/admin/notifications/stats'),
      client.get<ApiResponse<OutboxStatus>>('/api/admin/payments/outbox'),
      client.get<ApiResponse<OutboxStatus>>('/api/admin/outbox'),
    ]).then(([r, p, n, o, t]) => {
      if (r.status === 'fulfilled') setResv(r.value.data.data)
      if (p.status === 'fulfilled') setPay(p.value.data.data)
      if (n.status === 'fulfilled') setNotif(n.value.data.data)
      if (o.status === 'fulfilled') setOutbox(o.value.data.data)
      if (t.status === 'fulfilled') setTrainOutbox(t.value.data.data)
      if ([r, p, n, o, t].every(x => x.status === 'rejected')) setErr('통계를 불러오지 못했습니다. (권한/서비스 상태 확인)')
    })
  }, [])
  useEffect(() => { load() }, [load])

  return (
    <div className="fade-in">
      <SectionRow title="예약" onRefresh={load}>
        <Stat label="전체" value={resv?.total} />
        <Stat label="결제대기" value={resv?.pending} color={C.warning} />
        <Stat label="결제완료" value={resv?.paid} color={C.success} />
        <Stat label="취소" value={resv?.cancelled} color={C.textMuted} />
        <Stat label="매출(PAID)" value={resv ? won(resv.paidRevenue) : undefined} color={C.accent} />
      </SectionRow>

      <SectionRow title="결제">
        <Stat label="완료" value={pay?.completed} color={C.success} />
        <Stat label="실패" value={pay?.failed} color={C.danger} />
        <Stat label="환불" value={pay?.cancelled} color={C.textMuted} />
        <Stat label="결제 매출" value={pay ? won(pay.revenue) : undefined} color={C.accent} />
        <Stat label="환불액" value={pay ? won(pay.refundedAmount) : undefined} color={C.danger} />
      </SectionRow>

      <SectionRow title="알림 / Outbox">
        <Stat label="알림 발송" value={notif?.sent} color={C.success} />
        <Stat label="알림 실패" value={notif?.failed} color={notif && notif.failed > 0 ? C.danger : C.textMuted} />
        <Stat label="payment Outbox 발행" value={outbox?.sent} />
        <Stat label="payment Outbox 대기" value={outbox?.pending}
          color={outbox && outbox.pending > 0 ? C.warning : C.textMuted} />
        <Stat label="train Outbox 발행" value={trainOutbox?.sent} />
        <Stat label="train Outbox 대기" value={trainOutbox?.pending}
          color={trainOutbox && trainOutbox.pending > 0 ? C.warning : C.textMuted} />
        <Stat label="최장 미발행"
          value={outbox || trainOutbox
            ? `${Math.max(outbox?.oldestPendingAgeSeconds ?? 0, trainOutbox?.oldestPendingAgeSeconds ?? 0)}s`
            : undefined}
          color={Math.max(outbox?.oldestPendingAgeSeconds ?? 0, trainOutbox?.oldestPendingAgeSeconds ?? 0) > 10 ? C.danger : C.textMuted} />
      </SectionRow>

      {((outbox?.pending ?? 0) + (trainOutbox?.pending ?? 0)) > 0 && (
        <div style={{ background: C.warningBg, border: '1px solid #fde68a', borderRadius: 6, padding: '10px 14px', fontSize: 13, color: C.warning, marginTop: 4 }}>
          ⚠ 미발행 Outbox 이벤트 {(outbox?.pending ?? 0) + (trainOutbox?.pending ?? 0)}건 — relay 발행이 지연되고 있을 수 있습니다.
        </div>
      )}
      {err && <div style={{ color: C.danger, fontSize: 13, marginTop: 8 }}>{err}</div>}

      <div style={{ marginTop: 22 }}>
        <p style={{ fontSize: 11, fontWeight: 700, color: C.textMuted, textTransform: 'uppercase', letterSpacing: 0.8, margin: '0 0 8px' }}>외부 모니터링 도구</p>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <Ext href="http://localhost:3000" label="Grafana 대시보드" />
          <Ext href="http://localhost:9411" label="Zipkin 트레이스" />
          <Ext href="http://localhost:8082/swagger-ui/index.html" label="train Swagger" />
          <Ext href="http://localhost:8085/swagger-ui/index.html" label="payment Swagger" />
        </div>
      </div>
    </div>
  )
}

// ===== 예약 관리 =====
const RESV_STATUS = ['', 'PENDING', 'PAID', 'CANCELLED'] as const
function Reservations() {
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const [data, setData] = useState<Page<Reservation> | null>(null)
  const [busy, setBusy] = useState<number | null>(null)
  const [err, setErr] = useState('')

  const load = useCallback(() => {
    setErr('')
    client.get<ApiResponse<Page<Reservation>>>('/api/admin/reservations', { params: { status: status || undefined, page, size: 10 } })
      .then(({ data }) => setData(data.data))
      .catch(() => setErr('예약 목록 조회 실패'))
  }, [status, page])
  useEffect(() => { load() }, [load])

  const cancel = async (id: number, paid: boolean) => {
    if (!confirm(paid ? `예약 ${id}: 결제 완료 건입니다. 강제 취소하면 환불 사가가 시작됩니다. 진행할까요?` : `예약 ${id}을(를) 강제 취소할까요?`)) return
    setBusy(id)
    try {
      await client.post(`/api/admin/reservations/${id}/cancel`)
      load()
    } catch { alert('취소 실패') } finally { setBusy(null) }
  }

  return (
    <div className="fade-in">
      <div style={{ display: 'flex', gap: 6, marginBottom: 12 }}>
        {RESV_STATUS.map(s => (
          <button key={s || 'ALL'} onClick={() => { setStatus(s); setPage(0) }}
            style={{ padding: '6px 12px', fontSize: 12, fontWeight: 700, borderRadius: 4, cursor: 'pointer',
              border: `1px solid ${status === s ? C.accent : C.border}`,
              background: status === s ? C.accentLight : C.surface, color: status === s ? C.accent : C.textSub }}>
            {s || '전체'}
          </button>
        ))}
      </div>
      {err && <div style={{ color: C.danger, fontSize: 13, marginBottom: 8 }}>{err}</div>}
      <Table head={['예약', '유저', '상태', '금액', '좌석', '']}>
        {(data?.content ?? []).map(r => {
          const paid = r.status === 'PAID'
          return (
            <tr key={r.reservationId} style={{ borderTop: `1px solid ${C.border}` }}>
              <Td mono>No.{r.reservationId}</Td>
              <Td>{r.userId}</Td>
              <Td><StatusBadge status={r.status} /></Td>
              <Td>{won(r.totalPrice)}</Td>
              <Td>{r.tickets.map(t => t.seatNumber).join(', ')}</Td>
              <Td>
                {r.status !== 'CANCELLED' && (
                  <button onClick={() => cancel(r.reservationId, paid)} disabled={busy === r.reservationId}
                    style={{ padding: '5px 10px', fontSize: 12, fontWeight: 700, borderRadius: 4, cursor: 'pointer',
                      border: `1px solid ${C.danger}`, background: C.surface, color: C.danger }}>
                    {busy === r.reservationId ? '처리중' : (paid ? '취소·환불' : '강제취소')}
                  </button>
                )}
              </Td>
            </tr>
          )
        })}
      </Table>
      <Pager page={page} totalPages={data?.totalPages ?? 0} onChange={setPage} />
    </div>
  )
}

// ===== 대기열 제어 =====
type QueueScope = { scope: string; waiting: number; active: number }
type QueueMode = { mode: 'AUTO' | 'FORCE_ON' | 'FORCE_OFF'; maxActive: number; scopes: QueueScope[] }

const MODE_INFO: Record<QueueMode['mode'], { label: string; desc: string; color: string }> = {
  AUTO: { label: '자동 (AUTO)', desc: '평시 우회 + 동시 접속이 임계치를 넘으면 자동 대기열', color: C.success },
  FORCE_ON: { label: '강제 ON', desc: '부하와 무관하게 항상 대기열 — 명절·특별판매 대비', color: C.warning },
  FORCE_OFF: { label: '강제 OFF', desc: '대기열 비활성, 모두 즉시 입장 — 점검·저부하 확정 시', color: C.textMuted },
}

function QueueControl() {
  const [data, setData] = useState<QueueMode | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [err, setErr] = useState('')

  const load = useCallback(() => {
    setErr('')
    client.get<ApiResponse<QueueMode>>('/api/admin/queue/mode')
      .then(({ data }) => setData(data.data))
      .catch(() => setErr('대기열 상태를 불러오지 못했습니다. (권한/서비스 상태 확인)'))
  }, [])
  useEffect(() => { load() }, [load])

  const change = async (mode: QueueMode['mode']) => {
    if (mode === data?.mode) return
    setErr(''); setBusy(mode)
    try {
      const { data: res } = await client.put<ApiResponse<QueueMode>>('/api/admin/queue/mode', { mode })
      setData(res.data)
    } catch (e: unknown) {
      const msg = (e as { response?: { data?: ApiResponse<unknown> } })?.response?.data?.message
      setErr(msg ?? '모드 변경에 실패했습니다.')
    } finally { setBusy(null) }
  }

  return (
    <div className="fade-in">
      <SectionRow title="입장 제어 모드" onRefresh={load}>
        <Stat label="현재 모드" value={data ? MODE_INFO[data.mode].label : undefined} color={data ? MODE_INFO[data.mode].color : undefined} />
        <Stat label="동시 입장 임계치" value={data?.maxActive} color={C.accent} />
      </SectionRow>

      <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', margin: '4px 0 8px' }}>
        {(Object.keys(MODE_INFO) as QueueMode['mode'][]).map(m => {
          const active = data?.mode === m
          const info = MODE_INFO[m]
          return (
            <button key={m} onClick={() => change(m)} disabled={!!busy || active}
              style={{
                flex: '1 1 220px', textAlign: 'left', padding: '14px 16px', borderRadius: 8, cursor: active || busy ? 'default' : 'pointer',
                background: active ? C.accentLight : C.surface, border: `1.5px solid ${active ? C.accent : C.border}`,
                opacity: busy && !active ? 0.6 : 1,
              }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
                <span style={{ width: 9, height: 9, borderRadius: '50%', background: info.color, display: 'inline-block' }} />
                <span style={{ fontSize: 14, fontWeight: 800, color: active ? C.accent : C.text }}>{info.label}</span>
                {active && <span style={{ marginLeft: 'auto', fontSize: 11, fontWeight: 700, color: C.accent }}>적용 중</span>}
                {busy === m && <span style={{ marginLeft: 'auto', fontSize: 11, color: C.textMuted }}>적용 중…</span>}
              </div>
              <p style={{ margin: 0, fontSize: 12, color: C.textSub, lineHeight: 1.45 }}>{info.desc}</p>
            </button>
          )
        })}
      </div>

      {err && <div style={{ color: C.danger, fontSize: 13, margin: '8px 0' }}>{err}</div>}

      <div style={{ marginTop: 14 }}>
        <p style={{ fontSize: 11, fontWeight: 700, color: C.textMuted, textTransform: 'uppercase', letterSpacing: 0.8, margin: '0 0 8px' }}>scope별 현황</p>
        <Table head={['Scope', '대기 인원', '입장(active) 인원']}>
          {(data?.scopes ?? []).map(s => (
            <tr key={s.scope}>
              <Td mono>{s.scope}</Td>
              <Td>{s.waiting}</Td>
              <Td>{s.active}</Td>
            </tr>
          ))}
        </Table>
      </div>
    </div>
  )
}

// ===== 사가 로그 =====
function SagaLogs() {
  const [resvId, setResvId] = useState('')
  const [page, setPage] = useState(0)
  const [data, setData] = useState<Page<SagaLog> | null>(null)

  const load = useCallback(() => {
    client.get<ApiResponse<Page<SagaLog>>>('/api/admin/saga-logs', { params: { reservationId: resvId || undefined, page, size: 15 } })
      .then(({ data }) => setData(data.data)).catch(() => setData(null))
  }, [resvId, page])
  useEffect(() => { load() }, [load])

  return (
    <div className="fade-in">
      <div style={{ display: 'flex', gap: 6, marginBottom: 12 }}>
        <input value={resvId} onChange={e => { setResvId(e.target.value.replace(/\D/g, '')); setPage(0) }}
          placeholder="예약 ID로 필터 (비우면 전체)"
          style={{ padding: '7px 12px', fontSize: 13, border: `1px solid ${C.border}`, borderRadius: 4, width: 220 }} />
      </div>
      <Table head={['시각', '예약', '방향', '이벤트']}>
        {(data?.content ?? []).map(l => (
          <tr key={l.id} style={{ borderTop: `1px solid ${C.border}` }}>
            <Td muted>{new Date(l.observedAt).toLocaleString('ko-KR', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })}</Td>
            <Td mono>No.{l.reservationId}</Td>
            <Td><span style={{ fontSize: 11, fontWeight: 700, padding: '2px 7px', borderRadius: 3,
              background: l.direction === 'OUTBOUND' ? C.accentLight : '#fef3c7', color: l.direction === 'OUTBOUND' ? C.accent : C.warning }}>{l.direction}</span></Td>
            <Td mono>{l.eventType}</Td>
          </tr>
        ))}
      </Table>
      <Pager page={page} totalPages={data?.totalPages ?? 0} onChange={setPage} />
    </div>
  )
}

// ===== DLT 알림 (3개 서비스 통합) =====
type DltRow = DltAlert & { service: string }
function DltAlerts() {
  const [rows, setRows] = useState<DltRow[]>([])
  const [loaded, setLoaded] = useState(false)

  const load = useCallback(() => {
    const sources: { svc: string; url: string }[] = [
      { svc: 'train', url: '/api/admin/dlt-alerts' },
      { svc: 'payment', url: '/api/admin/payments/dlt-alerts' },
      { svc: 'notification', url: '/api/admin/notifications/dlt-alerts' },
    ]
    Promise.allSettled(sources.map(s =>
      client.get<ApiResponse<Page<DltAlert>>>(s.url, { params: { size: 50 } })
        .then(({ data }) => (data.data?.content ?? []).map(a => ({ ...a, service: s.svc })))
    )).then(results => {
      const merged = results.flatMap(r => r.status === 'fulfilled' ? r.value : [])
      merged.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
      setRows(merged); setLoaded(true)
    })
  }, [])
  useEffect(() => { load() }, [load])

  return (
    <div className="fade-in">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <span style={{ fontSize: 13, color: C.textSub }}>격리된 메시지 {rows.length}건 (train·payment·notification 통합)</span>
        <button onClick={load} style={{ padding: '6px 12px', fontSize: 12, fontWeight: 700, border: `1px solid ${C.border}`, background: C.surface, borderRadius: 4, cursor: 'pointer', color: C.textSub }}>새로고침</button>
      </div>
      {loaded && rows.length === 0 ? (
        <div style={{ background: C.successBg, border: '1px solid #6ee7b7', borderRadius: 6, padding: '16px', fontSize: 13, color: C.success }}>
          격리된(DLT) 메시지가 없습니다. 모든 이벤트가 정상 처리되었습니다.
        </div>
      ) : (
        <Table head={['시각', '서비스', '토픽', '키', '오류']}>
          {rows.map(r => (
            <tr key={`${r.service}-${r.id}`} style={{ borderTop: `1px solid ${C.border}` }}>
              <Td muted>{new Date(r.createdAt).toLocaleString('ko-KR', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })}</Td>
              <Td>{r.service}</Td>
              <Td mono>{r.topic}</Td>
              <Td mono>{r.recordKey ?? '-'}</Td>
              <Td muted>{r.errorMessage ?? '-'}</Td>
            </tr>
          ))}
        </Table>
      )}
    </div>
  )
}

// ===== 공용 UI =====
function SectionRow({ title, onRefresh, children }: { title: string; onRefresh?: () => void; children: React.ReactNode }) {
  return (
    <div style={{ marginBottom: 16 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, margin: '0 0 8px' }}>
        <p style={{ fontSize: 11, fontWeight: 700, color: C.textMuted, textTransform: 'uppercase', letterSpacing: 0.8, margin: 0 }}>{title}</p>
        {onRefresh && <button onClick={onRefresh} style={{ background: 'none', border: 'none', color: C.accent, cursor: 'pointer', fontSize: 12, fontWeight: 700 }}>↻ 새로고침</button>}
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: 8 }}>{children}</div>
    </div>
  )
}
function Stat({ label, value, color }: { label: string; value?: number | string; color?: string }) {
  return (
    <div style={{ background: C.surface, border: `1px solid ${C.border}`, borderRadius: 6, padding: '12px 14px' }}>
      <div style={{ fontSize: 11, color: C.textMuted, marginBottom: 4 }}>{label}</div>
      <div style={{ fontSize: 18, fontWeight: 800, color: color ?? C.text, letterSpacing: -0.3 }}>
        {value === undefined ? '—' : value}
      </div>
    </div>
  )
}
function Ext({ href, label }: { href: string; label: string }) {
  return (
    <a href={href} target="_blank" rel="noreferrer"
      style={{ padding: '8px 14px', background: C.surface, border: `1px solid ${C.border}`, borderRadius: 6, fontSize: 13, fontWeight: 600, color: C.accent, textDecoration: 'none' }}>
      {label} ↗
    </a>
  )
}
function Table({ head, children }: { head: string[]; children: React.ReactNode }) {
  return (
    <div style={{ background: C.surface, border: `1px solid ${C.border}`, borderRadius: 6, overflow: 'hidden' }}>
      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
        <thead>
          <tr style={{ background: C.bg }}>
            {head.map((h, i) => (
              <th key={i} style={{ textAlign: 'left', padding: '9px 14px', fontSize: 11, fontWeight: 700, color: C.textMuted, textTransform: 'uppercase', letterSpacing: 0.5 }}>{h}</th>
            ))}
          </tr>
        </thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  )
}
function Td({ children, mono, muted }: { children: React.ReactNode; mono?: boolean; muted?: boolean }) {
  return <td style={{ padding: '9px 14px', color: muted ? C.textMuted : C.text, fontFamily: mono ? 'monospace' : undefined, fontSize: mono ? 12 : 13 }}>{children}</td>
}
function StatusBadge({ status }: { status: string }) {
  const map: Record<string, { c: string; bg: string }> = {
    PAID: { c: C.success, bg: C.successBg }, PENDING: { c: C.warning, bg: C.warningBg },
    CANCELLED: { c: C.textMuted, bg: C.bg },
  }
  const m = map[status] ?? map.CANCELLED
  return <span style={{ fontSize: 11, fontWeight: 700, padding: '2px 8px', borderRadius: 3, background: m.bg, color: m.c }}>{status}</span>
}
function Pager({ page, totalPages, onChange }: { page: number; totalPages: number; onChange: (p: number) => void }) {
  if (totalPages <= 1) return null
  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', gap: 12, marginTop: 14 }}>
      <button onClick={() => onChange(page - 1)} disabled={page === 0}
        style={{ padding: '6px 12px', fontSize: 12, border: `1px solid ${C.border}`, background: C.surface, borderRadius: 4, cursor: page === 0 ? 'default' : 'pointer', color: page === 0 ? C.textMuted : C.text }}>이전</button>
      <span style={{ fontSize: 12, color: C.textSub }}>{page + 1} / {totalPages}</span>
      <button onClick={() => onChange(page + 1)} disabled={page >= totalPages - 1}
        style={{ padding: '6px 12px', fontSize: 12, border: `1px solid ${C.border}`, background: C.surface, borderRadius: 4, cursor: page >= totalPages - 1 ? 'default' : 'pointer', color: page >= totalPages - 1 ? C.textMuted : C.text }}>다음</button>
    </div>
  )
}
