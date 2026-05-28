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

  // Incoming frames are coalesced and written to xterm at most once per ~16ms
  // (~60/s). A burst of output (e.g. wake-init.sh pulling many repos at once)
  // would otherwise fire one term.write per frame and overwhelm the DOM
  // renderer, freezing the UI on a real device.
  const writeBufRef = useRef<Uint8Array[]>([]);
  const flushTimerRef = useRef<number | null>(null);

  const teardown = useCallback(() => {
    if (flushTimerRef.current != null) {
      window.clearTimeout(flushTimerRef.current);
      flushTimerRef.current = null;
    }
    writeBufRef.current = [];
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
    // Clear the PTY screen/scrollback so a later Connect starts on a clean
    // terminal — reconnecting always opens a fresh shell, not a resumed one.
    term?.reset();
    setState('idle');
  }, [teardown, term]);

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

    // Concatenate all buffered frames into one Uint8Array and write it in a
    // single call, so xterm decodes the combined byte stream (UTF-8 sequences
    // that straddle a frame boundary stay intact).
    const flush = () => {
      flushTimerRef.current = null;
      const chunks = writeBufRef.current;
      if (chunks.length === 0) {
        return;
      }
      writeBufRef.current = [];

      let data: Uint8Array;
      if (chunks.length === 1) {
        data = chunks[0];
      } else {
        let total = 0;
        for (const c of chunks) {
          total += c.length;
        }
        data = new Uint8Array(total);
        let offset = 0;
        for (const c of chunks) {
          data.set(c, offset);
          offset += c.length;
        }
      }
      // Pin to the bottom so the active input line stays in view as output
      // streams in. xterm 5.x has no `scrollOnOutput` option, so we scroll in
      // the write-completion callback (runs after the parser finishes this
      // chunk — the equivalent behavior). The synchronous call is a harmless
      // backup for the first paint before the callback fires.
      term.write(data, () => term.scrollToBottom());
      term.scrollToBottom();
    };

    ws.onmessage = (ev: MessageEvent) => {
      const chunk =
        typeof ev.data === 'string'
          ? encoderRef.current.encode(ev.data)
          : new Uint8Array(ev.data as ArrayBuffer);
      writeBufRef.current.push(chunk);
      // Coalesce a burst into a single write on the next ~16ms tick.
      if (flushTimerRef.current == null) {
        flushTimerRef.current = window.setTimeout(flush, 16);
      }
    };

    ws.onerror = () => {
      if (!intentionalRef.current) {
        setState('error');
      }
    };

    ws.onclose = () => {
      // Render any tail still buffered before tearing the socket down.
      if (flushTimerRef.current != null) {
        window.clearTimeout(flushTimerRef.current);
      }
      flush();
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

  // Sends raw input bytes to the shell — same path as xterm.onData, used by the
  // accessory bar (arrows, Ctrl combos, paste, …). No-op if the socket is not
  // open so callers don't need to mirror connection state.
  const sendInput = useCallback((data: string) => {
    const ws = wsRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(encoderRef.current.encode(data));
    }
  }, []);

  // Tear down on unmount.
  useEffect(() => {
    return () => {
      intentionalRef.current = true;
      teardown();
    };
  }, [teardown]);

  return { state, connect, disconnect, sendInput };
}
