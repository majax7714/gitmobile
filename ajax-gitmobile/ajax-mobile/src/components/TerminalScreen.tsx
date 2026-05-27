import { useEffect, useRef, useState } from 'react';
import type { Terminal } from '@xterm/xterm';
import { createTerminal, type ManagedTerminal } from '../lib/terminal';
import { useRelaySession } from '../hooks/useRelaySession';
import { useShellSocket } from '../hooks/useShellSocket';
import { useHeartbeat } from '../hooks/useHeartbeat';
import { StatusPill } from './StatusPill';
import { WakeButton } from './WakeButton';

export function TerminalScreen({ onResetToken }: { onResetToken: () => void }) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const managedRef = useRef<ManagedTerminal | null>(null);
  const [term, setTerm] = useState<Terminal | null>(null);
  const [online, setOnline] = useState(true);

  const { tracked, waking, error, wake } = useRelaySession();
  const { state: shellState, connect, disconnect } = useShellSocket(term);

  // Heartbeat only while a shell is actually connected.
  useHeartbeat(shellState === 'connected');

  // Mount the xterm terminal once.
  useEffect(() => {
    const el = containerRef.current;
    if (!el) {
      return;
    }
    const managed = createTerminal(el);
    managedRef.current = managed;
    setTerm(managed.term);

    const onResize = () => managed.fit.fit();
    window.addEventListener('resize', onResize);

    return () => {
      window.removeEventListener('resize', onResize);
      managed.dispose();
      managedRef.current = null;
      setTerm(null);
    };
  }, []);

  // Refit once a shell becomes active (layout has settled by then).
  useEffect(() => {
    if (shellState === 'connected') {
      const id = window.setTimeout(() => managedRef.current?.fit.fit(), 0);
      return () => window.clearTimeout(id);
    }
    return undefined;
  }, [shellState]);

  // Surface network changes; a real drop is also caught by the socket's onclose.
  useEffect(() => {
    setOnline(navigator.onLine);
    const goOnline = () => setOnline(true);
    const goOffline = () => setOnline(false);
    window.addEventListener('online', goOnline);
    window.addEventListener('offline', goOffline);
    return () => {
      window.removeEventListener('online', goOnline);
      window.removeEventListener('offline', goOffline);
    };
  }, []);

  const running = tracked === 'RUNNING';
  const connected = shellState === 'connected';
  const connecting = shellState === 'connecting';
  const dropped = shellState === 'disconnected' || shellState === 'error';
  const canConnect = running && term != null && !connected && !connecting;

  return (
    <div className="screen terminal-screen">
      <header className="bar">
        <div className="bar-left">
          <span className="app-title">Ajax Relay</span>
          <StatusPill status={tracked} />
        </div>
        <button className="link-btn" onClick={onResetToken}>
          Token
        </button>
      </header>

      <div className="controls">
        <WakeButton tracked={tracked} waking={waking} onWake={() => void wake()} />
        {connected ? (
          <button className="ghost-btn" onClick={disconnect}>
            Disconnect
          </button>
        ) : (
          <button
            className="primary-btn"
            onClick={() => void connect()}
            disabled={!canConnect}
          >
            {connecting ? 'Connecting…' : dropped ? 'Reconnect' : 'Connect'}
          </button>
        )}
      </div>

      {!online && <div className="banner banner-warn">Network offline</div>}
      {dropped && (
        <div className="banner banner-warn">
          Shell disconnected — tap Reconnect for a fresh session.
        </div>
      )}
      {error && <div className="banner banner-error">{error}</div>}

      <div className="terminal-host" ref={containerRef} />
    </div>
  );
}
