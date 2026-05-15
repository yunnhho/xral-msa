import { Routes, Route } from 'react-router-dom'

function App() {
  return (
    <Routes>
      <Route path="/" element={<div>XRail — 홈</div>} />
      <Route path="/oauth/callback" element={<div>OAuth 콜백 처리 중...</div>} />
    </Routes>
  )
}

export default App
