import { useCallback } from 'react';
import { WS_URL } from '../config';
import { getToken } from '../lib/secureStore';

// Runs a one-off command on the dev box over a short-lived shell WebSocket,
// separate from the user's interactive terminal so list-fetching never
// pollutes their session. The relay shell is a PTY, so it echoes the command
// back and wraps everything in prompt noise; we bracket the real output with
// unique markers and slice between them.
//
// Because the PTY echoes the typed line (which itself contains both markers),
// we take the LAST occurrence of BEGIN — the echoed copy comes first, the real
// output marker comes after the command actually runs.
export function useShellExec() {
  const exec = useCallback(
    (command: string, timeoutMs = 20_000): Promise<string> => {
      return new Promise((resolve, reject) => {
        void (async () => {
          const token = await getToken();
          if (!token) {
            reject(new Error('No bearer token saved'));
            return;
          }

          const id = Math.random().toString(36).slice(2, 10);
          const BEGIN = `__EXEC_BEGIN_${id}__`;
          const END = `__EXEC_END_${id}__`;

          const ws = new WebSocket(`${WS_URL}?token=${encodeURIComponent(token)}`);
          ws.binaryType = 'arraybuffer';

          const encoder = new TextEncoder();
          const decoder = new TextDecoder();
          let buf = '';
          let done = false;

          const timer = window.setTimeout(
            () => finish(new Error('Command timed out')),
            timeoutMs,
          );

          function finish(err: Error | null, value?: string) {
            if (done) {
              return;
            }
            done = true;
            window.clearTimeout(timer);
            ws.onopen = ws.onmessage = ws.onerror = ws.onclose = null;
            try {
              ws.close();
            } catch {
              /* ignore */
            }
            if (err) {
              reject(err);
            } else {
              resolve(value ?? '');
            }
          }

          ws.onopen = () => {
            // printf the markers on their own lines so they never collide with
            // the JSON payload between them.
            ws.send(
              encoder.encode(
                `printf '\\n${BEGIN}\\n'; ${command}; printf '\\n${END}\\n'\n`,
              ),
            );
          };

          ws.onmessage = (ev: MessageEvent) => {
            buf +=
              typeof ev.data === 'string'
                ? ev.data
                : decoder.decode(new Uint8Array(ev.data as ArrayBuffer), {
                    stream: true,
                  });
            const beginIdx = buf.lastIndexOf(BEGIN);
            if (beginIdx === -1) {
              return;
            }
            const endIdx = buf.indexOf(END, beginIdx + BEGIN.length);
            if (endIdx === -1) {
              return;
            }
            finish(null, buf.slice(beginIdx + BEGIN.length, endIdx));
          };

          ws.onerror = () => finish(new Error('Shell connection failed'));
          ws.onclose = () =>
            finish(new Error('Shell closed before the command finished'));
        })();
      });
    },
    [],
  );

  return { exec };
}
