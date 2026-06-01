import { useCallback, useEffect, useRef, useState } from 'react'
import client, { getAccessToken } from '../api/client'
import type { ApiResponse, QueueStatus } from '../types/api'

const POLL_INTERVAL_MS = 2000
const SSE_ERROR_THRESHOLD = 2

interface UseQueueStatusResult {
  status: QueueStatus | null
  error: string | null
  mode: 'sse' | 'polling' | 'idle'
  leave: () => Promise<void>
}

export function useQueueStatus(scope: string, enabled: boolean): UseQueueStatusResult {
  const [status, setStatus] = useState<QueueStatus | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [mode, setMode] = useState<'sse' | 'polling' | 'idle'>('idle')

  const esRef = useRef<EventSource | null>(null)
  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const sseErrorCount = useRef(0)
  const closed = useRef(false)

  const stopPolling = useCallback(() => {
    if (pollTimerRef.current) {
      clearInterval(pollTimerRef.current)
      pollTimerRef.current = null
    }
  }, [])

  const stopSSE = useCallback(() => {
    if (esRef.current) {
      esRef.current.close()
      esRef.current = null
    }
  }, [])

  const startPolling = useCallback(() => {
    if (pollTimerRef.current || closed.current) return
    setMode('polling')

    const poll = async () => {
      try {
        const { data } = await client.get<ApiResponse<QueueStatus>>(
          `/api/queue/status?scope=${scope}`,
        )
        if (data.data) setStatus(data.data)
      } catch {
        setError('큐 상태를 가져올 수 없습니다.')
      }
    }

    poll()
    pollTimerRef.current = setInterval(poll, POLL_INTERVAL_MS)
  }, [scope])

  const startSSE = useCallback(() => {
    if (esRef.current || closed.current) return
    sseErrorCount.current = 0
    setMode('sse')

    const token = getAccessToken()
    const tokenParam = token ? `&token=${encodeURIComponent(token)}` : ''
    const es = new EventSource(`/api/queue/subscribe?scope=${scope}${tokenParam}`, {
      withCredentials: true,
    } as EventSourceInit)
    esRef.current = es

    es.addEventListener('rank', (e: MessageEvent) => {
      try {
        const parsed = JSON.parse(e.data) as Omit<QueueStatus, 'scope' | 'status'>
        setStatus((prev) => ({
          scope,
          status: 'WAITING',
          ...prev,
          ...parsed,
        }))
        sseErrorCount.current = 0
      } catch {
        // ignore parse errors
      }
    })

    es.addEventListener('active', (e: MessageEvent) => {
      try {
        const parsed = JSON.parse(e.data) as { queueToken: string; expiresAt: string }
        setStatus({ scope, status: 'ACTIVE', ...parsed })
        es.close()
        esRef.current = null
        stopPolling()
      } catch {
        // ignore
      }
    })

    es.onerror = () => {
      sseErrorCount.current += 1
      if (sseErrorCount.current >= SSE_ERROR_THRESHOLD) {
        stopSSE()
        startPolling()
      }
    }
  }, [scope, startPolling, stopPolling, stopSSE])

  useEffect(() => {
    if (!enabled) return
    closed.current = false
    startSSE()

    return () => {
      closed.current = true
      stopSSE()
      stopPolling()
    }
  }, [enabled, startSSE, stopSSE, stopPolling])

  const leave = useCallback(async () => {
    closed.current = true
    stopSSE()
    stopPolling()
    await client.post('/api/queue/leave').catch(() => {})
    setStatus(null)
    setMode('idle')
  }, [stopSSE, stopPolling])

  return { status, error, mode, leave }
}
