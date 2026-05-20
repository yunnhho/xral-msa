export interface ApiResponse<T> {
  success: boolean
  code: string
  message: string
  data: T | null
  timestamp: string
}

export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

// Auth
export interface TokenPair {
  accessToken: string
  refreshToken: string
  tokenType: string
  accessExpiresIn: number
  refreshExpiresIn: number
  user: { userId: number; name: string; role: string }
}

export interface GuestRegisterResponse {
  userId: number
  accessCode: string
  accessToken: string
  refreshToken: string
  role: string
}

// Station / Schedule
export interface Station {
  stationId: number
  name: string
}

export interface RouteStation extends Station {
  sequence: number
  cumulativeDistance: number
}

export interface Route {
  routeId: number
  name: string
  stations: RouteStation[]
}

export interface StationsResponse {
  stations: Station[]
  routes: Route[]
}

export interface Schedule {
  scheduleId: number
  trainId: number
  trainNumber: string
  trainType: string
  routeId: number
  routeName: string
  departureDate: string
  departureTime: string
  arrivalTime: string
  departureStation: Station
  arrivalStation: Station
  duration: string
  estimatedPrice: number
  availableSeats: number
}

// Seats
export interface SeatInfo {
  seatId: number
  seatNumber: string
  available: boolean
  price: number
}

export interface CarriageInfo {
  carriageId: number
  carriageNumber: number
  seats: SeatInfo[]
}

export interface SeatsResponse {
  scheduleId: number
  segment: { startIdx: number; endIdx: number }
  carriages: CarriageInfo[]
}

// Reservation
export interface TicketSummary {
  ticketId: number
  seatId: number
  seatNumber: string
  price: number
}

export interface Reservation {
  reservationId: number
  status: 'PENDING' | 'PAID' | 'CANCELLED'
  totalPrice: number
  reservedAt: string
  expiresAt?: string
  tickets: TicketSummary[]
}

// Queue
export interface QueueStatus {
  scope: string
  status: 'WAITING' | 'ACTIVE'
  rank?: number
  totalWaiting?: number
  expectedWaitSeconds?: number
  queueToken?: string
  expiresAt?: string
}

// Payment
export interface PaymentResult {
  paymentId: number
  reservationId: number
  status: 'COMPLETED' | 'FAILED'
  amount: number
  method: string
  providerTxnId?: string
  completedAt?: string
}

// Notification
export interface Notification {
  notificationId: number
  channel: string
  template: string
  title: string
  body: string
  createdAt: string
  readAt: string | null
}
