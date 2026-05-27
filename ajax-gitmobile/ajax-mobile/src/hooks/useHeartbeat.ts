import { useEffect, useRef } from 'react';
import { App, type AppState } from '@capacitor/app';
import type { PluginListenerHandle } from '@capacitor/core';
import { sendHeartbeat } from '../lib/api';
import { HEARTBEAT_INTERVAL_MS } from '../config';

// Posts a heartbeat every HEARTBEAT_INTERVAL_MS while `active` is true and the
// app is foregrounded. Pauses when the app backgrounds (Capacitor lifecycle)
// and resumes on return. This drives the backend's idle auto-stop.
export function useHeartbeat(active: boolean) {
  const timerRef = useRef<number | null>(null);
  const foregroundRef = useRef(true);

  useEffect(() => {
    if (!active) {
      return;
    }

    let cancelled = false;
    let handle: PluginListenerHandle | undefined;

    const beat = () => {
      if (foregroundRef.current) {
        // Heartbeat failures are non-fatal; swallow so we don't crash the UI.
        void sendHeartbeat().catch(() => undefined);
      }
    };

    const start = () => {
      if (timerRef.current != null) {
        return;
      }
      beat();
      timerRef.current = window.setInterval(beat, HEARTBEAT_INTERVAL_MS);
    };

    const stop = () => {
      if (timerRef.current != null) {
        window.clearInterval(timerRef.current);
        timerRef.current = null;
      }
    };

    start();

    void App.addListener('appStateChange', (s: AppState) => {
      foregroundRef.current = s.isActive;
      if (s.isActive) {
        start();
      } else {
        stop();
      }
    }).then((h) => {
      if (cancelled) {
        void h.remove();
      } else {
        handle = h;
      }
    });

    return () => {
      cancelled = true;
      stop();
      void handle?.remove();
    };
  }, [active]);
}
