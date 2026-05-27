import { useState } from 'react';
import { setToken } from '../lib/secureStore';

export function SetupScreen({ onSaved }: { onSaved: () => void }) {
  const [value, setValue] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const save = async () => {
    const trimmed = value.trim();
    if (!trimmed) {
      setError('Token cannot be empty');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await setToken(trimmed);
      onSaved();
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };

  return (
    <div className="screen setup-screen">
      <div className="setup-card">
        <h1>Ajax Relay</h1>
        <p className="setup-hint">
          Paste your relay bearer token to connect. It's stored on this device
          only.
        </p>
        <input
          className="token-input"
          type="password"
          inputMode="text"
          autoComplete="off"
          autoCorrect="off"
          autoCapitalize="off"
          spellCheck={false}
          placeholder="Bearer token"
          value={value}
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              void save();
            }
          }}
        />
        {error && <div className="banner banner-error">{error}</div>}
        <button className="primary-btn" onClick={() => void save()} disabled={busy}>
          {busy ? 'Saving…' : 'Save & continue'}
        </button>
      </div>
    </div>
  );
}
