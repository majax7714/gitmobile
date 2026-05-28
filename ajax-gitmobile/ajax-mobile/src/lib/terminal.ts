import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import { WebLinksAddon } from '@xterm/addon-web-links';
import { Browser } from '@capacitor/browser';
import '@xterm/xterm/css/xterm.css';

export interface ManagedTerminal {
  term: Terminal;
  fit: FitAddon;
  dispose: () => void;
}

// Creates an xterm.js terminal with a dark theme and the fit addon attached
// for responsive mobile sizing.
export function createTerminal(container: HTMLElement): ManagedTerminal {
  const term = new Terminal({
    cursorBlink: true,
    // A single, predictable monospace stack keeps glyph cells uniform so
    // box-drawing / TUI output (e.g. Claude Code) lines up.
    fontFamily: 'Menlo, Monaco, "Courier New", monospace',
    fontSize: 14,
    lineHeight: 1.2,
    letterSpacing: 0,
    scrollback: 2000,
    // Lets addons opt into proposed xterm APIs.
    allowProposedApi: true,
    theme: {
      // High-contrast near-black background with a bright, full 16-color ANSI
      // palette so 256-color / true-color TUIs render cleanly on mobile.
      background: '#0a0a0a',
      foreground: '#e5e5e5',
      cursor: '#57c7ff',
      cursorAccent: '#0a0a0a',
      selectionBackground: '#3a3d41',
      black: '#1a1a1a',
      red: '#ff5f56',
      green: '#5af78e',
      yellow: '#f3f99d',
      blue: '#57c7ff',
      magenta: '#ff6ac1',
      cyan: '#9aedfe',
      white: '#e5e5e5',
      brightBlack: '#686868',
      brightRed: '#ff7b72',
      brightGreen: '#73e89a',
      brightYellow: '#fdfcb6',
      brightBlue: '#79c0ff',
      brightMagenta: '#ff92d0',
      brightCyan: '#b3f5ff',
      brightWhite: '#ffffff',
    },
  });

  const fit = new FitAddon();
  term.loadAddon(fit);

  // URLs in terminal output (e.g. OAuth device-flow links) become tappable.
  // The custom handler overrides the addon's default behavior, which would call
  // window.open and try to navigate the WebView; instead we hand the URL to the
  // Capacitor Browser plugin so it opens in the system browser (Safari View
  // Controller on iOS). preventDefault stops any residual default navigation.
  const webLinks = new WebLinksAddon((event, uri) => {
    event.preventDefault();
    void Browser.open({ url: uri });
  });
  term.loadAddon(webLinks);

  term.open(container);
  fit.fit();

  return {
    term,
    fit,
    dispose: () => term.dispose(),
  };
}
