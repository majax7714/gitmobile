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
    const handles: PluginListenerHandle[] = [];
    const track = (p: Promise<PluginListenerHandle>) => {
      void p.then((h) => handles.push(h));
    };

    // Will* events fire before the animation (drive the responsive UI swap);
    // Did* events fire after it settles (re-fit against the final size).
    track(
      Keyboard.addListener('keyboardWillShow', (info) => {
        root.style.setProperty('--kb-height', `${info.keyboardHeight}px`);
        onChange?.(true);
      }),
    );
    track(
      Keyboard.addListener('keyboardDidShow', (info) => {
        root.style.setProperty('--kb-height', `${info.keyboardHeight}px`);
        onChange?.(true);
      }),
    );
    track(
      Keyboard.addListener('keyboardWillHide', () => {
        root.style.setProperty('--kb-height', '0px');
        onChange?.(false);
      }),
    );
    track(
      Keyboard.addListener('keyboardDidHide', () => {
        root.style.setProperty('--kb-height', '0px');
        onChange?.(false);
      }),
    );

    return () => {
      for (const h of handles) {
        void h.remove();
      }
      root.style.setProperty('--kb-height', '0px');
    };
  }, [onChange]);
}
