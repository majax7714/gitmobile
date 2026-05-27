import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
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
  term.open(container);
  fit.fit();

  return {
    term,
    fit,
    dispose: () => term.dispose(),
  };
}
