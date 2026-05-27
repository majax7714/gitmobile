import { useCallback, useEffect, useRef, useState } from 'react';
import { getStatus, startSession, stopSession, type SessionStatus } from '../lib/api';
import { COLD_START_TIMEOUT_MS, STATUS_POLL_INTERVAL_MS } from '../config';

export type TrackedStatus = 'STOPPED' | 'STARTING' | 'RUNNING' | 'UNKNOWN';

function normalize(raw: string | undefined): TrackedStatus {
  switch ((raw ?? '').toUpperCase()) {
    case 'RUNNING':
      return 'RUNNING';
    case 'STARTING':
    case 'PENDING':
      return 'STARTING';
    case 'STOPPED':
    case 'STOPPING':
      return 'STOPPED';
    default:
      return 'UNKNOWN';
  }
}

// Owns the relay session lifecycle: a steady status poll (drives the pill),
// plus wake/stop actions. `waking` stays true from a wake() until the box
// reports RUNNING or the cold-start window elapses.
export function useRelaySession() {
  const [status, setStatus] = useState<SessionStatus | null>(null);
  const [waking, setWaking] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const wakingRef = useRef(false);
  const deadlineRef = useRef(0);

  const refresh = useCallback(async (): Promise<SessionStatus | null> => {
    try {
      const s = await getStatus();
      setStatus(s);
      setError(null);
      if (wakingRef.current && normalize(s.trackedStatus) === 'RUNNING') {
        wakingRef.current = false;
        setWaking(false);
      }
      return s;
    } catch (e) {
      setError((e as Error).message);
      return null;
    }
  }, []);

  useEffect(() => {
    void refresh();
    const id = window.setInterval(() => {
      if (wakingRef.current && Date.now() > deadlineRef.current) {
        wakingRef.current = false;
        setWaking(false);
        setError('Cold start timed out — tap Wake to retry.');
      }
      void refresh();
    }, STATUS_POLL_INTERVAL_MS);
    return () => window.clearInterval(id);
  }, [refresh]);

  const wake = useCallback(async () => {
    setError(null);
    wakingRef.current = true;
    setWaking(true);
    deadlineRef.current = Date.now() + COLD_START_TIMEOUT_MS;
    try {
      await startSession();
      await refresh();
    } catch (e) {
      wakingRef.current = false;
      setWaking(false);
      setError((e as Error).message);
    }
  }, [refresh]);

  const stop = useCallback(async () => {
    try {
      await stopSession();
      await refresh();
    } catch (e) {
      setError((e as Error).message);
    }
  }, [refresh]);

  return {
    status,
    tracked: normalize(status?.trackedStatus),
    waking,
    error,
    wake,
    stop,
    refresh,
  };
}
