import { useEffect } from 'react';
import { Capacitor } from '@capacitor/core';
import type { PluginListenerHandle } from '@capacitor/core';
import { Keyboard } from '@capacitor/keyboard';

// With KeyboardResize.None the WebView does not shrink when the keyboard opens,
// so we lift the layout ourselves: set --kb-height to the keyboard height and
// the app shell reserves it as bottom padding (see .app-shell in CSS), pushing
// the terminal/accessory-bar/tab-nav column up above the keyboard. The onChange
// callback lets the caller refit xterm once the layout settles.
export function useKeyboardInset(onChange?: () => void) {
  useEffect(() => {
    // The plugin is native-only; skip on web/dev where it is a no-op.
    if (!Capacitor.isNativePlatform()) {
      return;
    }

    const root = document.documentElement;
    let showHandle: PluginListenerHandle | undefined;
    let hideHandle: PluginListenerHandle | undefined;

    void Keyboard.addListener('keyboardWillShow', (info) => {
      root.style.setProperty('--kb-height', `${info.keyboardHeight}px`);
      onChange?.();
    }).then((h) => (showHandle = h));

    void Keyboard.addListener('keyboardWillHide', () => {
      root.style.setProperty('--kb-height', '0px');
      onChange?.();
    }).then((h) => (hideHandle = h));

    return () => {
      void showHandle?.remove();
      void hideHandle?.remove();
      root.style.setProperty('--kb-height', '0px');
    };
  }, [onChange]);
}
