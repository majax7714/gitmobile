import { API_BASE } from '../config';
import { getToken } from './secureStore';

export interface SessionStatus {
  awsStatus: string;
  trackedStatus: string;
  instanceId: string;
  lastActive: string;
}

/** Raised when no bearer token is saved yet. */
export class MissingTokenError extends Error {
  constructor() {
    super('No bearer token saved');
    this.name = 'MissingTokenError';
  }
}

/** Raised when the relay rejects the token. */
export class UnauthorizedError extends Error {
  constructor() {
    super('Unauthorized — the saved token was rejected');
    this.name = 'UnauthorizedError';
  }
}

// Single fetch wrapper for all REST calls. Injects the bearer header from
// secure storage. The token itself is never logged or included in errors.
async function authFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const token = await getToken();
  if (!token) {
    throw new MissingTokenError();
  }
  const headers = new Headers(init.headers);
  headers.set('Authorization', `Bearer ${token}`);

  const res = await fetch(`${API_BASE}${path}`, { ...init, headers });
  if (res.status === 401) {
    throw new UnauthorizedError();
  }
  return res;
}

export async function getStatus(): Promise<SessionStatus> {
  const res = await authFetch('/session/status');
  if (!res.ok) {
    throw new Error(`status request failed (${res.status})`);
  }
  return (await res.json()) as SessionStatus;
}

export async function startSession(): Promise<void> {
  const res = await authFetch('/session/start', { method: 'POST' });
  if (!res.ok) {
    throw new Error(`start request failed (${res.status})`);
  }
}

export async function stopSession(): Promise<void> {
  const res = await authFetch('/session/stop', { method: 'POST' });
  if (!res.ok) {
    throw new Error(`stop request failed (${res.status})`);
  }
}

export async function sendHeartbeat(): Promise<void> {
  const res = await authFetch('/session/heartbeat', { method: 'POST' });
  if (!res.ok) {
    throw new Error(`heartbeat request failed (${res.status})`);
  }
}

interface ExecResult {
  stdout: string;
  exitCode: number;
}

/**
 * Runs an allowlisted command on the dev box via a dedicated SSH exec channel
 * and returns its raw stdout. Unlike the interactive shell WebSocket, this gives
 * pristine program output (no prompt/echo/ANSI), so callers can JSON.parse the
 * result directly. The command must match the relay's server-side allowlist.
 */
export async function execCommand(cmd: string): Promise<string> {
  const res = await authFetch('/exec', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ cmd }),
  });
  if (!res.ok) {
    let msg = `exec request failed (${res.status})`;
    try {
      const body = (await res.json()) as { error?: string };
      if (body?.error) {
        msg = body.error;
      }
    } catch {
      /* non-JSON error body; keep the status message */
    }
    throw new Error(msg);
  }
  const data = (await res.json()) as ExecResult;
  return data.stdout;
}
