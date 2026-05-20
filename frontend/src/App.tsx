import { Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider, useAuth } from './contexts/AuthContext'
import GuestLoginPage from './pages/GuestLoginPage'
import HomePage from './pages/HomePage'
import LoginPage from './pages/LoginPage'
import OAuthCallbackPage from './pages/OAuthCallbackPage'
import PaymentPage from './pages/PaymentPage'
import QueuePage from './pages/QueuePage'
import ReservationsPage from './pages/ReservationsPage'
import SeatPage from './pages/SeatPage'
import SignupPage from './pages/SignupPage'

function RequireAuth({ children }: { children: React.ReactNode }) {
  const { isAuthenticated, isLoading } = useAuth()
  if (isLoading) return <div style={{ textAlign: 'center', marginTop: 100 }}>로딩 중...</div>
  return isAuthenticated ? <>{children}</> : <Navigate to="/login" replace />
}

function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/home" replace />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/signup" element={<SignupPage />} />
      <Route path="/guest/login" element={<GuestLoginPage />} />
      <Route path="/oauth/callback" element={<OAuthCallbackPage />} />
      <Route path="/home" element={<RequireAuth><HomePage /></RequireAuth>} />
      <Route path="/queue" element={<RequireAuth><QueuePage /></RequireAuth>} />
      <Route path="/seats" element={<RequireAuth><SeatPage /></RequireAuth>} />
      <Route path="/payment" element={<RequireAuth><PaymentPage /></RequireAuth>} />
      <Route path="/reservations" element={<RequireAuth><ReservationsPage /></RequireAuth>} />
      <Route path="*" element={<Navigate to="/home" replace />} />
    </Routes>
  )
}

export default function App() {
  return (
    <AuthProvider>
      <AppRoutes />
    </AuthProvider>
  )
}
