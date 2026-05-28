export type Tab = 'terminal' | 'repos';

interface Props {
  active: Tab;
  onChange: (tab: Tab) => void;
}

const TABS: { id: Tab; label: string; icon: string }[] = [
  { id: 'terminal', label: 'Terminal', icon: '▷_' },
  { id: 'repos', label: 'Repos', icon: '⌥' },
];

// Two-tab bottom navigation. State-based (no router) to match the app's
// existing screen-switching pattern in App.tsx.
export function BottomTabBar({ active, onChange }: Props) {
  return (
    <nav className="tab-bar" role="tablist" aria-label="Sections">
      {TABS.map((t) => (
        <button
          key={t.id}
          role="tab"
          aria-selected={active === t.id}
          className={`tab-btn${active === t.id ? ' tab-btn-active' : ''}`}
          onClick={() => onChange(t.id)}
        >
          <span className="tab-icon" aria-hidden>
            {t.icon}
          </span>
          <span className="tab-label">{t.label}</span>
        </button>
      ))}
    </nav>
  );
}
