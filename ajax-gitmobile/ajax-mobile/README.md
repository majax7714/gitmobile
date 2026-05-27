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

## Backend endpoints

All REST endpoints require an `Authorization: Bearer <token>` header.

| Method | Path                     | Purpose                              |
| ------ | ------------------------ | ------------------------------------ |
| GET    | `/api/session/status`    | `{awsStatus, trackedStatus, instanceId, lastActive}` |
| POST   | `/api/session/start`     | Start the EC2 dev box (idempotent)   |
| POST   | `/api/session/stop`      | Stop the EC2 dev box                 |
| POST   | `/api/session/heartbeat` | Reset the idle timer                 |

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
├── App.tsx               routes between Setup and Terminal screens
├── config.ts             relay host, URLs, intervals, timeouts
├── components/
│   ├── SetupScreen.tsx   first-run token paste
│   ├── TerminalScreen.tsx xterm mount + controls + reconnect
│   ├── StatusPill.tsx    STOPPED/STARTING/RUNNING indicator
│   └── WakeButton.tsx    /start + cold-start progress
├── hooks/
│   ├── useRelaySession.ts start/stop/status + polling
│   ├── useShellSocket.ts  WebSocket ↔ xterm byte piping
│   └── useHeartbeat.ts    30s heartbeat, paused in background
├── lib/
│   ├── api.ts            fetch wrapper, injects Bearer header
│   ├── secureStore.ts    Capacitor Preferences token storage
│   └── terminal.ts       xterm setup, fit addon, dark theme
└── styles/
    └── terminal.css      dark, mobile-safe layout
```

The token is never written to logs.
