# Mobile feature work — tabs, repo browser, scripted wake, terminal polish

This document records the second major feature pass on **ajax-mobile**: bottom
tab navigation, a finder-style repository browser, a scripted wake flow, and
terminal/keyboard polish. It also captures the design decisions behind each, so
the rationale survives the diff.

> **Credential model:** there is **no in-app GitHub credential handling**. The
> dev box's `gh` auth is the single source of truth, and every GitHub operation
> runs as a shell command on the dev box (`gh repo list`, `gh repo clone`, …).
> No tokens are stored client-side. The only secret on the device is the relay
> bearer token (unchanged from before).

## 1. App shell & bottom tab navigation

`AppShell` is the root authed view (replacing the old `TerminalScreen`). It owns
everything that must outlive a tab switch:

- the **single xterm instance** (mounted once),
- session state (`useRelaySession`), the shell socket (`useShellSocket`),
  heartbeat, and keyboard inset,
- the active tab (`'terminal' | 'repos'`).

Two tabs are presented by `BottomTabBar`: **Terminal** and **Repos**. Routing is
plain component state — no React Router — matching the existing Setup↔Terminal
switch in `App.tsx`.

**Key decision — hide, don't unmount.** The terminal pane is toggled with CSS
(`display:none`) rather than conditionally rendered. Unmounting xterm would tear
down the live shell on every tab switch. Because a hidden element has no
measurable size, `AppShell` refits xterm whenever the Terminal tab becomes
visible again (and on connect, window resize, and keyboard show/hide).

## 2. Top bar action cluster

The top bar (`TopBar`) is persistent across tabs: **status pill on the left**,
**actions on the right**. The action is a single button that morphs with state
so the bar never grows a second control:

```
not running        → Wake     (spinner + "Waking…" while in flight)
running, no shell  → Connect / Reconnect
shell connected    → Disconnect
```

Token reset lives beside it. The Wake/Connect/Disconnect buttons moved here from
the old inline `.controls` row so they're reachable from either tab.

## 3. Scripted wake (`wake-init.sh`)

Tapping **Wake** now does more than start the box. `AppShell` arms a one-shot
sequence:

1. `autoConnectRef` — once the box reports `RUNNING`, the shell WebSocket opens
   automatically (RUNNING ⇒ SSH is ready).
2. `pendingInputRef` — `bash ~/wake-init.sh\n` is queued and flushed as the first
   input the moment the shell connects.
3. The user is dropped on the **Terminal** tab to watch repo syncing.

**One-shot guarantee:** `pendingInputRef` is cleared immediately after sending,
so a later manual reconnect never re-runs the script. `Disconnect` clears both
refs. The `wake-init.sh` script already lives on the dev box; the app only
invokes it.

The same queue powers the Repos-tab actions (below): a command issued while the
shell is offline is queued and delivered on the next connect.

## 4. Repos tab — finder-style browser

`ReposScreen` lists GitHub repos and local clones. On mount (when the box is
running) and on refresh it runs **one** shell command via `useShellExec`:

```sh
gh repo list --json name,description,updatedAt,isPrivate --limit 100
printf '\n__REPO_SPLIT__\n'
ls -1 ~/repos 2>/dev/null || true
```

The output is split on the marker: the JSON half is parsed into rows; the `ls`
half becomes the set of local clones (a single `ls ~/repos` intersected with the
list — far cheaper than a per-repo existence check). Rows are sorted by
`updatedAt` and show: public/private icon, name, two-line-clamped description,
and a relative timestamp ("2d ago").

Each row has one action:

- **Open** (clone exists locally) → sends `cd ~/repos/<name>\n` to the main
  terminal and switches to the Terminal tab.
- **Clone** (not local) → sends `gh repo clone <name> ~/repos/<name>\n` and
  switches tabs so clone progress is visible.

Pull-to-refresh (engages only at scroll-top) and a header **Refresh** button both
re-fetch.

### `useShellExec` — the exec channel

Running a command without polluting the user's interactive terminal needs a
**separate, short-lived WebSocket** to the same relay shell endpoint. The relay
shell is a PTY, so it echoes the typed command and surrounds output with prompt
noise. The hook handles this by bracketing output in per-call random markers:

```sh
printf '\n__EXEC_BEGIN_<id>__\n'; <command>; printf '\n__EXEC_END_<id>__\n'
```

It then slices between **`lastIndexOf(BEGIN)`** and the following `END`. Using
the *last* BEGIN is the trick: the PTY's echo of the command line (which contains
the marker literally) comes first, and the real output marker comes after the
command actually executes. ANSI escapes and `\r` are stripped before parsing. A
20s timeout rejects a stuck command.

**Assumptions to validate on-device:** (a) the relay permits a second concurrent
shell connection; (b) a non-login PTY has `gh` on `PATH`. If `gh` isn't found,
wrap the exec command in a login shell (`bash -lc '…'`).

## 5. Terminal & keyboard polish

### Rendering

`createTerminal` (in `lib/terminal.ts`) now sets `fontSize:14`,
`lineHeight:1.2`, `letterSpacing:0`, a single predictable monospace stack
(`Menlo, Monaco, "Courier New", monospace`), and `allowProposedApi:true`. The
theme is high-contrast near-black (`#0a0a0a` / `#e5e5e5`) with a full 16-color
ANSI palette so 256-/true-color TUIs (e.g. Claude Code) render cleanly and line
up. xterm 5.x already uses the **DOM renderer** by default — that, plus the
`user-select` CSS override, keeps native long-press selection working.

### Keyboard layout (`KeyboardResize.None` + manual lift)

`capacitor.config.ts` sets `plugins.Keyboard.resize = KeyboardResize.None`, so
the WebView itself never resizes when the keyboard appears. Instead `AppShell` is
`position: fixed` with `bottom: var(--kb-inset)`, and `useKeyboardInset` listens
to `keyboardWillShow/Hide` to set `--kb-inset` to the keyboard height and refit
xterm. The accessory bar and terminal input stay flush above the keyboard,
under our control rather than the OS's. The listener is native-only (no-op on
web/dev).

## 6. Preserved functionality

These earlier features were explicitly kept working:

- Accessory bar (Esc/Tab/Ctrl/arrows, Copy/Paste, URL field).
- DOM renderer + `user-select` CSS for native long-press selection.
- Copy (prefers `term.getSelection()`, falls back to `window.getSelection()`).
- URL tap-to-open via `@xterm/addon-web-links` → `Browser.open()` (system Safari).
- `term.reset()` clear-on-disconnect.

## File map of this change

| File | Change |
| ---- | ------ |
| `components/AppShell.tsx` | **new** — root shell: terminal mount, session, tabs, wake-init one-shot, repo→terminal routing |
| `components/TopBar.tsx` | **new** — persistent status + morphing action button + Token |
| `components/BottomTabBar.tsx` | **new** — Terminal / Repos tabs |
| `components/ReposScreen.tsx` | **new** — repo list, local-clone intersection, Open/Clone, pull-to-refresh |
| `hooks/useShellExec.ts` | **new** — one-off command exec over a short-lived WS with marker extraction |
| `hooks/useKeyboardInset.ts` | **new** — keyboard show/hide → `--kb-inset` + refit |
| `lib/terminal.ts` | theme/font polish, `allowProposedApi` |
| `styles/terminal.css` | `--kb-inset`, fixed app-shell, tab content/bar, top-bar actions, repos list |
| `capacitor.config.ts` | `Keyboard.resize = None` |
| `App.tsx` | renders `AppShell` |
| `components/TerminalScreen.tsx`, `components/WakeButton.tsx` | **removed** — logic moved into `AppShell` + `TopBar` |

Native plugin added: `@capacitor/keyboard`. Re-synced with `npx cap sync ios`
(installs the Keyboard pod; verified `KeyboardPlugin` + `resize:none` in the
generated native config).
