import { useEffect, useState } from 'react';
import { clearToken, getToken } from './lib/secureStore';
import { SetupScreen } from './components/SetupScreen';
import { AppShell } from './components/AppShell';

type Screen = 'loading' | 'setup' | 'terminal';

export default function App() {
  const [screen, setScreen] = useState<Screen>('loading');

  useEffect(() => {
    void getToken().then((t) => setScreen(t ? 'terminal' : 'setup'));
  }, []);

  if (screen === 'loading') {
    return <div className="app-loading">Loading…</div>;
  }

  if (screen === 'setup') {
    return <SetupScreen onSaved={() => setScreen('terminal')} />;
  }

  return (
    <AppShell
      onResetToken={() => {
        void clearToken().then(() => setScreen('setup'));
      }}
    />
  );
}
