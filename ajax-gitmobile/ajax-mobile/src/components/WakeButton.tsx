import type { TrackedStatus } from '../hooks/useRelaySession';

interface Props {
  tracked: TrackedStatus;
  waking: boolean;
  onWake: () => void;
}

export function WakeButton({ tracked, waking, onWake }: Props) {
  const running = tracked === 'RUNNING';
  const label = running ? 'Dev box running' : waking ? 'Waking… (45–90s)' : 'Wake dev box';

  return (
    <button className="wake-btn" onClick={onWake} disabled={waking || running}>
      {waking && <span className="spinner" aria-hidden />}
      {label}
    </button>
  );
}
