import type { TrackedStatus } from '../hooks/useRelaySession';

const LABELS: Record<TrackedStatus, string> = {
  STOPPED: 'Stopped',
  STARTING: 'Starting',
  RUNNING: 'Running',
  UNKNOWN: 'Unknown',
};

export function StatusPill({ status }: { status: TrackedStatus }) {
  return (
    <span className={`status-pill status-${status.toLowerCase()}`}>
      <span className="status-dot" aria-hidden />
      {LABELS[status]}
    </span>
  );
}
