import { C } from '../styles/theme'

const STEPS = ['열차 선택', '예매 대기', '좌석 선택', '결제']

export default function BookingSteps({ current }: { current: 0 | 1 | 2 | 3 }) {
  return (
    <div style={{ background: C.surface, borderBottom: `1px solid ${C.border}` }}>
      <div style={{ maxWidth: 640, margin: '0 auto', padding: '0 20px', display: 'flex', alignItems: 'center', height: 44 }}>
        {STEPS.map((step, i) => (
          <div key={step} style={{ display: 'flex', alignItems: 'center', flex: i < STEPS.length - 1 ? 1 : 'none' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <div style={{ width: 22, height: 22, borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 10, fontWeight: 800, background: i < current ? C.accent : i === current ? C.accent : C.bgAlt, color: i <= current ? '#fff' : C.textMuted, flexShrink: 0 }}>
                {i < current ? '✓' : i + 1}
              </div>
              <span style={{ fontSize: 12, fontWeight: i === current ? 700 : 400, color: i === current ? C.text : i < current ? C.textSub : C.textMuted, whiteSpace: 'nowrap' }}>
                {step}
              </span>
            </div>
            {i < STEPS.length - 1 && (
              <div style={{ flex: 1, height: 1, background: i < current ? C.accent : C.border, margin: '0 8px', opacity: i < current ? 0.5 : 1 }} />
            )}
          </div>
        ))}
      </div>
    </div>
  )
}
