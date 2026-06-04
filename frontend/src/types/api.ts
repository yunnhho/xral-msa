export interface ApiResponse<T> {
  success: boolean
  code: string
  message: string
  data: T | null
  timestamp: string
}

// Auth — matches backend LoginResponse (flat structure)
export interface TokenPair {
  userId: number
  name: string
  role: string
  accessToken: string
  refreshToken: string
}

export interface MeResponse {
  userId: number
  name: string
  email: string
  role: string
}

// Station / Schedule
export interface Station {
  stationId: number
  name: string
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
  userId: number
  status: 'PENDING' | 'PAID' | 'CANCELLED' | 'EXPIRED'
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

// ===== Admin =====
export interface Page<T> {
  content: T[]
  totalPages: number
  totalElements: number
  number: number
}

export interface ReservationStats {
  total: number
  pending: number
  paid: number
  cancelled: number
  paidRevenue: number
}

export interface PaymentStats {
  total: number
  requested: number
  completed: number
  failed: number
  cancelled: number
  revenue: number
  refundedAmount: number
}

export interface NotificationStats {
  total: number
  sent: number
  pending: number
  failed: number
}

export interface OutboxStatus {
  pending: number
  sent: number
  oldestPendingAgeSeconds: number | null
}

export interface DltAlert {
  id: number
  topic: string
  partition: number
  offset: number
  recordKey?: string
  recordValue?: string
  errorMessage?: string
  createdAt: string
}

export interface SagaLog {
  id: number
  reservationId: number
  eventType: string
  direction: string
  payloadJson: string
  observedAt: string
}

