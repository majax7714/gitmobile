import { useCallback, useEffect, useRef, useState, type TouchEvent } from 'react';
import { useShellExec } from '../hooks/useShellExec';

interface Repo {
  name: string;
  description: string;
  updatedAt: string;
  isPrivate: boolean;
}

interface RepoRow extends Repo {
  local: boolean;
}

interface Props {
  // The dev box must be RUNNING for the exec socket to reach a shell.
  running: boolean;
  onOpenRepo: (name: string) => void;
  onCloneRepo: (name: string) => void;
}

const SPLIT = '__REPO_SPLIT__';

// One round-trip: list repos as JSON, then list local clones. The marker keeps
// the two payloads separable in a single shell command.
const LIST_COMMAND =
  `gh repo list --json name,description,updatedAt,isPrivate --limit 100; ` +
  `printf '\\n${SPLIT}\\n'; ls -1 ~/repos 2>/dev/null || true`;

// CSI escape sequences + stray carriage returns the PTY may interleave.
function clean(s: string): string {
  // eslint-disable-next-line no-control-regex
  return s.replace(/\x1b\[[0-9;?]*[ -/]*[@-~]/g, '').replace(/\r/g, '');
}

function relativeTime(iso: string): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) {
    return '';
  }
  const s = Math.max(0, (Date.now() - then) / 1000);
  if (s < 60) return 'just now';
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.floor(h / 24);
  if (d < 30) return `${d}d ago`;
  const mo = Math.floor(d / 30);
  if (mo < 12) return `${mo}mo ago`;
  return `${Math.floor(d / 365)}y ago`;
}

export function ReposScreen({ running, onOpenRepo, onCloneRepo }: Props) {
  const { exec } = useShellExec();
  const [rows, setRows] = useState<RepoRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pull, setPull] = useState(0);

  const scrollRef = useRef<HTMLDivElement | null>(null);
  const pullStartRef = useRef<number | null>(null);

  const load = useCallback(async () => {
    if (!running) {
      setError('Wake the dev box to load repositories.');
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const raw = clean(await exec(LIST_COMMAND));
      const [jsonPart = '', lsPart = ''] = raw.split(SPLIT);
      const repos = JSON.parse(jsonPart.trim()) as Repo[];
      const local = new Set(
        lsPart
          .split('\n')
          .map((l) => l.trim())
          .filter(Boolean),
      );
      const merged = repos
        .map((r) => ({ ...r, local: local.has(r.name) }))
        .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
      setRows(merged);
    } catch (e) {
      setError(
        `Could not load repositories: ${(e as Error).message}. ` +
          `Check that gh is authenticated on the dev box.`,
      );
    } finally {
      setLoading(false);
    }
  }, [exec, running]);

  // Fetch on first mount and whenever the box transitions to running.
  useEffect(() => {
    if (running) {
      void load();
    }
  }, [running, load]);

  // Lightweight pull-to-refresh: only engages when already scrolled to the top.
  const onTouchStart = (e: TouchEvent<HTMLDivElement>) => {
    if ((scrollRef.current?.scrollTop ?? 0) <= 0) {
      pullStartRef.current = e.touches[0].clientY;
    }
  };
  const onTouchMove = (e: TouchEvent<HTMLDivElement>) => {
    if (pullStartRef.current == null) {
      return;
    }
    const delta = e.touches[0].clientY - pullStartRef.current;
    setPull(delta > 0 ? Math.min(delta, 80) : 0);
  };
  const onTouchEnd = () => {
    if (pull > 56 && !loading) {
      void load();
    }
    pullStartRef.current = null;
    setPull(0);
  };

  return (
    <div className="repos-pane">
      <div className="repos-head">
        <span className="repos-title">Repositories</span>
        <button
          className="link-btn"
          onClick={() => void load()}
          disabled={loading || !running}
        >
          {loading ? 'Loading…' : 'Refresh'}
        </button>
      </div>

      {error && <div className="banner banner-warn repos-banner">{error}</div>}

      <div
        className="repos-list"
        ref={scrollRef}
        onTouchStart={onTouchStart}
        onTouchMove={onTouchMove}
        onTouchEnd={onTouchEnd}
      >
        {pull > 0 && (
          <div className="repos-pull" style={{ height: pull }}>
            {pull > 56 ? 'Release to refresh' : 'Pull to refresh'}
          </div>
        )}

        {rows.length === 0 && !loading && !error && (
          <div className="repos-empty">No repositories.</div>
        )}

        {rows.map((r) => (
          <div className="repo-row" key={r.name}>
            <div className="repo-main">
              <div className="repo-name-line">
                <span className="repo-vis" aria-hidden>
                  {r.isPrivate ? '🔒' : '🌐'}
                </span>
                <span className="repo-name">{r.name}</span>
                <span className="repo-updated">{relativeTime(r.updatedAt)}</span>
              </div>
              {r.description && (
                <div className="repo-desc">{r.description}</div>
              )}
            </div>
            <div className="repo-actions">
              {r.local ? (
                <button
                  className="repo-btn open"
                  onClick={() => onOpenRepo(r.name)}
                >
                  Open
                </button>
              ) : (
                <button
                  className="repo-btn clone"
                  onClick={() => onCloneRepo(r.name)}
                >
                  Clone
                </button>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
