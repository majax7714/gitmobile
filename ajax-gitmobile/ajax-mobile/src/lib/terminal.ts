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
    fontFamily: 'Menlo, Monaco, "SF Mono", "Courier New", monospace',
    fontSize: 13,
    scrollback: 2000,
    theme: {
      background: '#0d1117',
      foreground: '#c9d1d9',
      cursor: '#58a6ff',
      cursorAccent: '#0d1117',
      selectionBackground: '#264f78',
      black: '#484f58',
      red: '#ff7b72',
      green: '#3fb950',
      yellow: '#d29922',
      blue: '#58a6ff',
      magenta: '#bc8cff',
      cyan: '#39c5cf',
      white: '#b1bac4',
      brightBlack: '#6e7681',
      brightRed: '#ffa198',
      brightGreen: '#56d364',
      brightYellow: '#e3b341',
      brightBlue: '#79c0ff',
      brightMagenta: '#d2a8ff',
      brightCyan: '#56d4dd',
      brightWhite: '#f0f6fc',
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
