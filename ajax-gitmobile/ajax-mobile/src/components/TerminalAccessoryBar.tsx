import { useCallback, useState } from 'react';
import type { Terminal } from '@xterm/xterm';
import { Clipboard } from '@capacitor/clipboard';
import { Browser } from '@capacitor/browser';

interface Props {
  term: Terminal | null;
  sendInput: (data: string) => void;
  disabled: boolean;
}

// Mobile keyboards only emit printable characters, so this bar provides the
// special keys (Esc, Tab, arrows) and a Ctrl modifier the shell needs, plus
// clipboard actions and an external URL launcher for OAuth device flows.
export function TerminalAccessoryBar({ term, sendInput, disabled }: Props) {
  const [ctrlArmed, setCtrlArmed] = useState(false);
  const [urlOpen, setUrlOpen] = useState(false);
  const [url, setUrl] = useState('');
  const [busy, setBusy] = useState(false);
  const [hint, setHint] = useState<string | null>(null);

  const flash = useCallback((msg: string) => {
    setHint(msg);
    window.setTimeout(() => setHint((h) => (h === msg ? null : h)), 1500);
  }, []);

  const send = useCallback(
    (data: string) => {
      if (disabled) {
        return;
      }
      sendInput(data);
    },
    [disabled, sendInput],
  );

  // A letter pressed while Ctrl is armed becomes its control code
  // (Ctrl+C -> 0x03, Ctrl+D -> 0x04, …). Ctrl auto-releases after one combo.
  const handleLetter = useCallback(
    (letter: string) => {
      if (disabled) {
        return;
      }
      const code = letter.toUpperCase().charCodeAt(0) - 64;
      sendInput(String.fromCharCode(code));
      setCtrlArmed(false);
    },
    [disabled, sendInput],
  );

  const onCopy = useCallback(async () => {
    // With the DOM renderer, two selections can exist: xterm's own
    // programmatic selection (term.getSelection) and, on touch devices, the
    // OS-level selection the user makes via long-press (window.getSelection).
    // Prefer xterm's, fall back to the native one.
    const fromXterm = term?.getSelection() ?? '';
    const sel = fromXterm !== '' ? fromXterm : window.getSelection()?.toString() ?? '';
    if (sel === '') {
      flash('Nothing selected');
      return;
    }
    try {
      await Clipboard.write({ string: sel });
      flash('Copied');
    } catch {
      flash('Copy failed');
    }
  }, [term, flash]);

  const onPaste = useCallback(async () => {
    if (disabled) {
      return;
    }
    try {
      const { value, type } = await Clipboard.read();
      if (type && !type.startsWith('text')) {
        flash('Clipboard not text');
        return;
      }
      if (!value) {
        flash('Clipboard empty');
        return;
      }
      sendInput(value);
    } catch {
      flash('Paste failed');
    }
  }, [disabled, sendInput, flash]);

  const onOpenUrl = useCallback(async () => {
    const trimmed = url.trim();
    if (!trimmed) {
      return;
    }
    // Tolerate the user pasting a bare host; reject anything else upfront so we
    // don't hand a junk string to the system browser.
    const normalized = /^https?:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`;
    try {
      new URL(normalized);
    } catch {
      flash('Invalid URL');
      return;
    }
    setBusy(true);
    try {
      await Browser.open({ url: normalized });
      setUrlOpen(false);
      setUrl('');
    } catch {
      flash('Could not open URL');
    } finally {
      setBusy(false);
    }
  }, [url, flash]);

  // A render helper keeps each key consistent and small on screen.
  const KeyBtn = ({
    label,
    onClick,
    active,
    width,
  }: {
    label: string;
    onClick: () => void;
    active?: boolean;
    width?: number;
  }) => (
    <button
      type="button"
      className={`accy-key${active ? ' accy-key-active' : ''}`}
      onMouseDown={(e) => e.preventDefault()}
      onClick={onClick}
      disabled={disabled}
      style={width ? { minWidth: width } : undefined}
    >
      {label}
    </button>
  );

  // Ctrl-armed mode: tapping a letter sends a control code. We expose a small
  // letter strip in place of the regular keys so the touch flow is one-tap.
  const ctrlLetters = ['A', 'C', 'D', 'L', 'R', 'U', 'W', 'Z'];

  return (
    <div className="accy-wrap">
      {hint && <div className="accy-hint">{hint}</div>}

      {urlOpen && (
        <div className="accy-url-row">
          <input
            type="url"
            inputMode="url"
            autoCapitalize="none"
            autoCorrect="off"
            spellCheck={false}
            className="accy-url-input"
            placeholder="Paste URL (https://…)"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
          />
          <button
            type="button"
            className="accy-url-btn"
            onClick={() => void onOpenUrl()}
            disabled={busy || !url.trim()}
          >
            Open
          </button>
          <button
            type="button"
            className="accy-url-cancel"
            onClick={() => {
              setUrlOpen(false);
              setUrl('');
            }}
          >
            ×
          </button>
        </div>
      )}

      <div className="accy-bar" role="toolbar" aria-label="Terminal keys">
        {ctrlArmed ? (
          <>
            <KeyBtn label="Ctrl" onClick={() => setCtrlArmed(false)} active />
            {ctrlLetters.map((l) => (
              <KeyBtn key={l} label={l} onClick={() => handleLetter(l)} />
            ))}
          </>
        ) : (
          <>
            <KeyBtn label="Esc" onClick={() => send('\x1b')} />
            <KeyBtn label="Tab" onClick={() => send('\x09')} />
            <KeyBtn
              label="Ctrl"
              onClick={() => setCtrlArmed(true)}
              active={ctrlArmed}
            />
            <KeyBtn label="↑" onClick={() => send('\x1b[A')} />
            <KeyBtn label="↓" onClick={() => send('\x1b[B')} />
            <KeyBtn label="←" onClick={() => send('\x1b[D')} />
            <KeyBtn label="→" onClick={() => send('\x1b[C')} />
            <span className="accy-sep" aria-hidden />
            <KeyBtn label="Copy" onClick={() => void onCopy()} width={56} />
            <KeyBtn label="Paste" onClick={() => void onPaste()} width={56} />
            <KeyBtn label="URL" onClick={() => setUrlOpen((v) => !v)} width={48} />
          </>
        )}
      </div>
    </div>
  );
}
