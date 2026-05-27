import { useCallback, useEffect, useRef, useState } from 'react';
import type { IDisposable, Terminal } from '@xterm/xterm';
import { WS_URL } from '../config';
import { getToken } from '../lib/secureStore';

export type ShellState = 'idle' | 'connecting' | 'connected' | 'disconnected' | 'error';

// Manages the shell WebSocket and wires it to an xterm instance:
//   xterm keystrokes -> binary WS frames
//   binary WS frames -> xterm.write
// The bearer token is passed as a query param because webview WebSocket APIs
// cannot set an Authorization header. Reconnecting opens a fresh shell — no
// attempt is made to preserve PTY state.
export function useShellSocket(term: Terminal | null) {
  const [state, setState] = useState<ShellState>('idle');

  const wsRef = useRef<WebSocket | null>(null);
  const dataSubRef = useRef<IDisposable | null>(null);
  const intentionalRef = useRef(false);
  const encoderRef = useRef(new TextEncoder());

  const teardown = useCallback(() => {
    dataSubRef.current?.dispose();
    dataSubRef.current = null;
    const ws = wsRef.current;
    wsRef.current = null;
    if (ws) {
      // Detach handlers first so this teardown never flips state itself.
      ws.onopen = null;
      ws.onmessage = null;
      ws.onerror = null;
      ws.onclose = null;
      if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) {
        try {
          ws.close();
        } catch {
          /* ignore */
        }
      }
    }
  }, []);

  const disconnect = useCallback(() => {
    intentionalRef.current = true;
    teardown();
    setState('idle');
  }, [teardown]);

  const connect = useCallback(async () => {
    if (!term) {
      return;
    }
    teardown();
    intentionalRef.current = false;
    setState('connecting');

    const token = await getToken();
    if (!token) {
      setState('error');
      return;
    }

    const ws = new WebSocket(`${WS_URL}?token=${encodeURIComponent(token)}`);
    ws.binaryType = 'arraybuffer';
    wsRef.current = ws;

    ws.onopen = () => {
      setState('connected');
      dataSubRef.current = term.onData((data) => {
        if (ws.readyState === WebSocket.OPEN) {
          ws.send(encoderRef.current.encode(data));
        }
      });
      term.focus();
    };

    ws.onmessage = (ev: MessageEvent) => {
      if (typeof ev.data === 'string') {
        term.write(ev.data);
      } else {
        term.write(new Uint8Array(ev.data as ArrayBuffer));
      }
    };

    ws.onerror = () => {
      if (!intentionalRef.current) {
        setState('error');
      }
    };

    ws.onclose = () => {
      dataSubRef.current?.dispose();
      dataSubRef.current = null;
      wsRef.current = null;
      setState((prev) => {
        if (intentionalRef.current) {
          return 'idle';
        }
        return prev === 'error' ? 'error' : 'disconnected';
      });
    };
  }, [term, teardown]);

  // Tear down on unmount.
  useEffect(() => {
    return () => {
      intentionalRef.current = true;
      teardown();
    };
  }, [teardown]);

  return { state, connect, disconnect };
}
