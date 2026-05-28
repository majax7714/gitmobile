import { useCallback, useEffect, useRef, useState } from 'react';
import type { Terminal } from '@xterm/xterm';
import { createTerminal, type ManagedTerminal } from '../lib/terminal';
import { useRelaySession } from '../hooks/useRelaySession';
import { useShellSocket } from '../hooks/useShellSocket';
import { useHeartbeat } from '../hooks/useHeartbeat';
import { useKeyboardInset } from '../hooks/useKeyboardInset';
import { TopBar } from './TopBar';
import { BottomTabBar, type Tab } from './BottomTabBar';
import { TerminalAccessoryBar } from './TerminalAccessoryBar';
import { ReposScreen } from './ReposScreen';

// The persistent app shell: top bar + tabbed content + bottom nav. It owns the
// single xterm instance and the shell/session state so both the top bar and the
// Repos tab can drive the same terminal. The terminal is mounted once and only
// hidden (never unmounted) when the Repos tab is active, so the live shell
// survives tab switches.
export function AppShell({ onResetToken }: { onResetToken: () => void }) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const managedRef = useRef<ManagedTerminal | null>(null);
  const [term, setTerm] = useState<Terminal | null>(null);
  const [online, setOnline] = useState(true);
  const [tab, setTab] = useState<Tab>('terminal');
  // The accessory bar and the bottom tab nav are mutually exclusive: the nav
  // shows while typing is idle, the accessory bar takes its place (above the
  // keyboard) while typing. Flipped by the keyboard listeners below.
  const [keyboardVisible, setKeyboardVisible] = useState(false);

  const { tracked, waking, error, wake } = useRelaySession();
  const { state: shellState, connect, disconnect, sendInput } = useShellSocket(term);

  useHeartbeat(shellState === 'connected');

  // Any layout change (keyboard show/hide, tab switch, orientation, resize)
  // recomputes xterm's rows/cols and re-pins to the bottom. Debounced to ~50ms
  // so a burst of events fits once instead of thrashing.
  const refitTimerRef = useRef<number | null>(null);
  const scheduleRefit = useCallback(() => {
    if (refitTimerRef.current != null) {
      window.clearTimeout(refitTimerRef.current);
    }
    refitTimerRef.current = window.setTimeout(() => {
      refitTimerRef.current = null;
      const managed = managedRef.current;
      if (!managed) {
        return;
      }
      managed.fit.fit();
      // The fit may have shrunk the viewport (e.g. keyboard opening); keep the
      // active input line in view.
      managed.term.scrollToBottom();
    }, 50);
  }, []);

  // Keyboard show/hide swaps the accessory bar ⇄ tab nav, then refits so the
  // terminal absorbs the freed/lost space and the input line stays in view.
  const onKeyboardChange = useCallback(
    (visible: boolean) => {
      setKeyboardVisible(visible);
      scheduleRefit();
    },
    [scheduleRefit],
  );
  useKeyboardInset(onKeyboardChange);

  // Tapping the terminal focuses xterm, which raises the keyboard again after a
  // dismiss (must run inside the tap gesture for iOS to allow it).
  const focusTerminal = useCallback(() => {
    managedRef.current?.term.focus();
  }, []);

  // Mount the xterm terminal once.
  useEffect(() => {
    const el = containerRef.current;
    if (!el) {
      return;
    }
    const managed = createTerminal(el);
    managedRef.current = managed;
    setTerm(managed.term);

    // Re-fit on any host size change — keyboard, tab toggle, orientation, or
    // the accessory-bar/tab-nav swap all resize this element. fit() only
    // resizes xterm's rows/cols (not the host), so this can't loop.
    const ro = new ResizeObserver(() => scheduleRefit());
    ro.observe(el);

    return () => {
      ro.disconnect();
      if (refitTimerRef.current != null) {
        window.clearTimeout(refitTimerRef.current);
        refitTimerRef.current = null;
      }
      managed.dispose();
      managedRef.current = null;
      setTerm(null);
    };
  }, []);

  // Reflow on viewport changes — window resize and orientation flips.
  useEffect(() => {
    window.addEventListener('resize', scheduleRefit);
    window.addEventListener('orientationchange', scheduleRefit);
    return () => {
      window.removeEventListener('resize', scheduleRefit);
      window.removeEventListener('orientationchange', scheduleRefit);
    };
  }, [scheduleRefit]);

  // Refit when a shell connects, when the terminal tab becomes visible again
  // (it has no measurable size while display:none on the Repos tab), or when the
  // keyboard toggle adds/removes the accessory bar or tab nav.
  useEffect(() => {
    if (shellState === 'connected' || tab === 'terminal') {
      scheduleRefit();
    }
  }, [shellState, tab, keyboardVisible, scheduleRefit]);

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

  // --- Auto-connect + queued input -----------------------------------------
  // autoConnectRef: a wake we initiated should open the shell once RUNNING.
  // pendingInputRef: a line to deliver to the terminal as soon as it connects
  // (used for the wake-init script and for Repo tab actions).
  const autoConnectRef = useRef(false);
  const pendingInputRef = useRef<string | null>(null);

  // After an app-initiated wake reaches RUNNING, open the shell automatically.
  useEffect(() => {
    if (autoConnectRef.current && running && term != null && !connected && !connecting) {
      autoConnectRef.current = false;
      void connect();
    }
  }, [running, term, connected, connecting, connect]);

  // Flush a queued line once the shell is live. Cleared after sending, so it
  // never re-runs on a later reconnect — the wake-init is one-shot per wake.
  useEffect(() => {
    if (connected && pendingInputRef.current != null) {
      sendInput(pendingInputRef.current);
      pendingInputRef.current = null;
    }
  }, [connected, sendInput]);

  const handleWake = useCallback(() => {
    // On a successful wake: open the shell, run the startup script once, and
    // land the user on the terminal so they watch repo syncing.
    autoConnectRef.current = true;
    pendingInputRef.current = 'bash ~/wake-init.sh\n';
    setTab('terminal');
    void wake();
  }, [wake]);

  const handleDisconnect = useCallback(() => {
    autoConnectRef.current = false;
    pendingInputRef.current = null;
    disconnect();
  }, [disconnect]);

  // Run a command on the interactive terminal, connecting first if needed, and
  // switch to the terminal tab so the user sees the output.
  const runOnTerminal = useCallback(
    (line: string) => {
      setTab('terminal');
      if (connected) {
        sendInput(line);
      } else {
        pendingInputRef.current = line;
        if (canConnect) {
          void connect();
        }
      }
    },
    [connected, canConnect, connect, sendInput],
  );

  const openRepo = useCallback(
    (name: string) => runOnTerminal(`cd ~/repos/${name}\n`),
    [runOnTerminal],
  );
  const cloneRepo = useCallback(
    (name: string) => runOnTerminal(`gh repo clone ${name} ~/repos/${name}\n`),
    [runOnTerminal],
  );

  return (
    <div className="screen app-shell">
      <TopBar
        tracked={tracked}
        waking={waking}
        connecting={connecting}
        connected={connected}
        dropped={dropped}
        canConnect={canConnect}
        onWake={handleWake}
        onConnect={() => void connect()}
        onDisconnect={handleDisconnect}
        onResetToken={onResetToken}
      />

      {!online && <div className="banner banner-warn">Network offline</div>}
      {dropped && (
        <div className="banner banner-warn">
          Shell disconnected — tap Reconnect for a fresh session.
        </div>
      )}
      {error && <div className="banner banner-error">{error}</div>}

      <div className="tab-content">
        <div className={`tab-pane${tab === 'terminal' ? '' : ' tab-hidden'}`}>
          <div className="terminal-host" ref={containerRef} onClick={focusTerminal} />
          {/* Accessory bar only while typing — it replaces the tab nav and rides
              just above the keyboard. */}
          {keyboardVisible && (
            <TerminalAccessoryBar
              term={term}
              sendInput={sendInput}
              disabled={!connected}
            />
          )}
        </div>
        <div className={`tab-pane${tab === 'repos' ? '' : ' tab-hidden'}`}>
          <ReposScreen
            running={running}
            onOpenRepo={openRepo}
            onCloneRepo={cloneRepo}
          />
        </div>
      </div>

      {/* Tab nav only when the keyboard is down — hidden while typing so the
          accessory bar can take the bottom slot. */}
      {!keyboardVisible && <BottomTabBar active={tab} onChange={setTab} />}
    </div>
  );
}
