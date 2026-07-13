import { useNavigate, useLocation } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import { C } from '../styles/theme'

export default function NavBar() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const { pathname } = useLocation()

  const links = [
    { label: '승차권 예매', path: '/home' },
    { label: '예매 내역', path: '/reservations' },
    ...(user?.role === 'ROLE_ADMIN' ? [{ label: '운영자', path: '/admin' }] : []),
  ]

  return (
    <header style={{ background: C.brand, color: '#fff', position: 'sticky', top: 0, zIndex: 100 }}>
      <div style={{ maxWidth: 1080, margin: '0 auto', padding: '0 clamp(10px, 2.5vw, 24px)', display: 'flex', alignItems: 'center', height: 52 }}>
        {/* 로고 */}
        <button onClick={() => navigate('/home')}
          style={{ background: 'none', border: 'none', cursor: 'pointer', padding: 0, display: 'flex', alignItems: 'baseline', gap: 6 }}>
          <span style={{ color: '#fff', fontSize: 17, fontWeight: 900, letterSpacing: -0.5 }}>XRAIL</span>
          <span style={{ width: 6, height: 6, borderRadius: '50%', background: C.cta, display: 'inline-block', marginBottom: 1 }} />
        </button>

        {/* 네비 */}
        <nav style={{ display: 'flex', alignItems: 'center', marginLeft: 'clamp(8px, 2.5vw, 28px)', gap: 2 }}>
          {links.map(l => (
            <button key={l.path} onClick={() => navigate(l.path)}
              style={{ background: 'none', border: 'none', cursor: 'pointer', fontSize: 13, fontWeight: 500, padding: '6px clamp(6px, 1.5vw, 12px)', borderRadius: 4, color: pathname.startsWith(l.path) ? '#fff' : 'rgba(255,255,255,.55)', transition: 'color 0.1s', whiteSpace: 'nowrap' }}>
              {l.label}
            </button>
          ))}
        </nav>

        {/* 유저 */}
        <div style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 'clamp(6px, 1.5vw, 12px)' }}>
          <span style={{ fontSize: 13, color: 'rgba(255,255,255,.65)', letterSpacing: -0.2, whiteSpace: 'nowrap', maxWidth: 96, overflow: 'hidden', textOverflow: 'ellipsis' }}>{user?.name}</span>
          <button onClick={logout}
            style={{ background: 'rgba(255,255,255,.08)', border: '1px solid rgba(255,255,255,.15)', color: 'rgba(255,255,255,.75)', padding: '5px 12px', borderRadius: 4, cursor: 'pointer', fontSize: 12, whiteSpace: 'nowrap' }}>
            로그아웃
          </button>
        </div>
      </div>
    </header>
  )
}
