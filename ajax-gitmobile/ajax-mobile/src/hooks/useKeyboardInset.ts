import { useEffect } from 'react';
import { Capacitor } from '@capacitor/core';
import type { PluginListenerHandle } from '@capacitor/core';
import { Keyboard } from '@capacitor/keyboard';

// With KeyboardResize.None the WebView does not shrink when the keyboard opens,
// so we lift the layout ourselves: set --kb-height to the keyboard height and
// the app shell reserves it as bottom padding (see .app-shell in CSS), pushing
// the terminal/accessory-bar column up above the keyboard. The onChange callback
// reports visibility (true on show, false on hide) so the caller can swap the
// accessory bar for the tab nav and refit xterm once the layout settles.
export function useKeyboardInset(onChange?: (visible: boolean) => void) {
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
      onChange?.(true);
    }).then((h) => (showHandle = h));

    void Keyboard.addListener('keyboardWillHide', () => {
      root.style.setProperty('--kb-height', '0px');
      onChange?.(false);
    }).then((h) => (hideHandle = h));

    return () => {
      void showHandle?.remove();
      void hideHandle?.remove();
      root.style.setProperty('--kb-height', '0px');
    };
  }, [onChange]);
}
