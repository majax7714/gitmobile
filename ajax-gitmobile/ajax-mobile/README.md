# ajax-mobile

A Capacitor + React + TypeScript terminal client for the **ajax-relay** backend.
It wakes a remote EC2 dev box, shows its status, and opens an interactive shell
over a WebSocket — rendered with [xterm.js](https://xtermjs.org/).

## What it does

- **Setup screen** — paste your bearer token on first run; stored via
  `@capacitor/preferences` and reused on subsequent launches.
- **Wake button** — `POST /api/session/start`, then polls
  `GET /api/session/status` every 3s until `trackedStatus = RUNNING`
  (EC2 cold start is ~45–90s) before the shell can connect.
- **Status pill** — STOPPED / STARTING / RUNNING from polled status.
- **Terminal** — xterm.js with the fit addon for responsive mobile sizing;
  input/output is piped to the relay shell WebSocket as binary frames.
- **Heartbeat** — `POST /api/session/heartbeat` every 30s while the app is
  foregrounded and a shell is connected (pauses in the background via
  `@capacitor/app`). This drives the backend's idle auto-stop.
- **Reconnect** — on a dropped socket or network change, shows a disconnected
  state and offers a fresh-shell reconnect (PTY state is not preserved).
- **Bottom tab navigation** — Terminal and Repos tabs under a persistent top bar
  (status pill + Wake/Connect/Disconnect + Token). The xterm instance is mounted
  once and hidden (never unmounted) across tab switches.
- **Scripted wake** — a successful Wake auto-opens the shell, runs
  `~/wake-init.sh` once, and lands you on the Terminal tab to watch repo syncing.
- **Repos tab** — a finder-style list from `gh repo list` (run on the dev box,
  not in your interactive terminal), marking which repos are cloned under
  `~/repos`, with per-row Open / Clone actions.

See [`docs/mobile-features.md`](docs/mobile-features.md) for the architecture and
design decisions behind these.

> GitHub auth lives entirely on the dev box (`gh`); no GitHub tokens are stored
> on the device. The only client-side secret is the relay bearer token.

## Backend endpoints

All REST endpoints require an `Authorization: Bearer <token>` header.

| Method | Path                     | Purpose                              |
| ------ | ------------------------ | ------------------------------------ |
| GET    | `/api/session/status`    | `{awsStatus, trackedStatus, instanceId, lastActive}` |
| POST   | `/api/session/start`     | Start the EC2 dev box (idempotent)   |
| POST   | `/api/session/stop`      | Stop the EC2 dev box                 |
| POST   | `/api/session/heartbeat` | Reset the idle timer                 |
| POST   | `/api/exec`              | Run an allowlisted command over an SSH exec channel; returns `{stdout, exitCode}` |

The shell WebSocket is `wss://<host>/api/shell?token=<token>`. Webview
WebSocket APIs cannot set custom headers, so the relay accepts the bearer
token as a **query parameter** on this endpoint; the client uses that form.

## Configuration

Knobs live in [`src/config.ts`](src/config.ts). The relay host defaults to
`ajax-relay.kb3tonline.com` and can be overridden with `VITE_RELAY_HOST`
(see `.env.example`).

## Develop & build

```bash
npm install         # install dependencies
npm run dev         # Vite dev server in the browser (http://localhost:5173)
npm run build       # type-check (tsc) + production build into dist/
```

## Run on a device

```bash
# one-time: add the native platforms (creates ios/ and android/)
npx cap add ios
npx cap add android

# after every web build, copy dist/ + plugins into the native projects
npm run build
npx cap sync

# open the native IDE, then run on a simulator or a connected device
npx cap open ios        # Xcode  (requires macOS + Xcode)
npx cap open android    # Android Studio
```

Because the relay serves a valid Let's Encrypt certificate, `https://` and
`wss://` work on both platforms with no cleartext / ATS exceptions.

## Project layout

```
src/
├── main.tsx              React entry
├── App.tsx               routes between Setup and the app shell
├── config.ts             relay host, URLs, intervals, timeouts
├── components/
│   ├── SetupScreen.tsx   first-run token paste
│   ├── AppShell.tsx      root authed view: terminal mount + tabs + session
│   ├── TopBar.tsx        status pill + Wake/Connect/Disconnect + Token
│   ├── BottomTabBar.tsx  Terminal / Repos tab nav
│   ├── ReposScreen.tsx   gh repo list browser, Open/Clone actions
│   ├── TerminalAccessoryBar.tsx  Esc/Tab/Ctrl/arrows, Copy/Paste, URL field
│   └── StatusPill.tsx    STOPPED/STARTING/RUNNING indicator
├── hooks/
│   ├── useRelaySession.ts start/stop/status + polling
│   ├── useShellSocket.ts  WebSocket ↔ xterm byte piping (batched writes)
│   ├── useKeyboardInset.ts keyboard show/hide → manual layout lift
│   └── useHeartbeat.ts    30s heartbeat, paused in background
├── lib/
│   ├── api.ts            fetch wrapper + execCommand (POST /api/exec)
│   ├── secureStore.ts    Capacitor Preferences token storage
│   └── terminal.ts       xterm setup, fit + web-links addons, theme
└── styles/
    └── terminal.css      dark, mobile-safe layout
```

The token is never written to logs.
