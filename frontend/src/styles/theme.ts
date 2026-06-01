// XRail 전용 디자인 토큰
// 소재: 어두운 슬레이트 헤더 + 선명한 시안 액센트 + 레드 CTA
export const C = {
  // 표면
  surface:     '#ffffff',
  bg:          '#f1f5f9',
  bgAlt:       '#e2e8f0',

  // 텍스트
  text:        '#0f172a',
  textSub:     '#475569',
  textMuted:   '#94a3b8',

  // 브랜드 (헤더/네비)
  brand:       '#0f172a',   // 딥 슬레이트
  brandLight:  '#1e293b',

  // 액센트 (하이라이트, 선택, 링크)
  accent:      '#0891b2',   // 시안
  accentLight: '#e0f2fe',
  accentHover: '#0e7490',

  // CTA (결제, 예약 등 주요 액션)
  cta:         '#e11d48',   // 바이브런트 로즈레드
  ctaHover:    '#be123c',

  // 시스템
  success:     '#059669',
  successBg:   '#ecfdf5',
  warning:     '#d97706',
  warningBg:   '#fffbeb',
  danger:      '#dc2626',
  dangerBg:    '#fff1f2',

  // 구분선 / 보더
  border:      '#e2e8f0',
  borderStrong:'#cbd5e1',

  // 그림자 (절제)
  shadow:      '0 1px 3px rgba(15,23,42,.08)',
  shadowMd:    '0 4px 12px rgba(15,23,42,.10)',
} as const

// 열차 타입별 색상
export const TRAIN_BADGE: Record<string, string> = {
  KTX:  '#e11d48',
  ITX:  '#2563eb',
  SRT:  '#7c3aed',
  무궁화: '#059669',
  새마을: '#d97706',
}
