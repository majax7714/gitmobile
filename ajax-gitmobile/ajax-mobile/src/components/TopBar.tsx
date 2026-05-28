import { StatusPill } from './StatusPill';
import type { TrackedStatus } from '../hooks/useRelaySession';

interface Props {
  tracked: TrackedStatus;
  waking: boolean;
  connecting: boolean;
  connected: boolean;
  dropped: boolean;
  canConnect: boolean;
  onWake: () => void;
  onConnect: () => void;
  onDisconnect: () => void;
  onResetToken: () => void;
}

// Persistent top bar shared by every tab: status on the left, the session
// action (Wake → Connect/Disconnect) and Token on the right. Compact so it
// leaves maximum room for the terminal.
export function TopBar({
  tracked,
  waking,
  connecting,
  connected,
  dropped,
  canConnect,
  onWake,
  onConnect,
  onDisconnect,
  onResetToken,
}: Props) {
  const running = tracked === 'RUNNING';

  // One slot that morphs with state, so the bar never grows a second button.
  let action;
  if (!running) {
    action = (
      <button className="top-action wake" onClick={onWake} disabled={waking}>
        {waking && <span className="spinner" aria-hidden />}
        {waking ? 'Waking…' : 'Wake'}
      </button>
    );
  } else if (connected) {
    action = (
      <button className="top-action ghost" onClick={onDisconnect}>
        Disconnect
      </button>
    );
  } else {
    action = (
      <button
        className="top-action primary"
        onClick={onConnect}
        disabled={!canConnect}
      >
        {connecting ? 'Connecting…' : dropped ? 'Reconnect' : 'Connect'}
      </button>
    );
  }

  return (
    <header className="bar top-bar">
      <div className="bar-left">
        <StatusPill status={tracked} />
      </div>
      <div className="bar-right">
        {action}
        <button className="link-btn" onClick={onResetToken}>
          Token
        </button>
      </div>
    </header>
  );
}
