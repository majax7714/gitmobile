package com.ajax.relay.controller;

import com.ajax.relay.service.SessionStateService;
import com.ajax.relay.service.SshSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.session.ClientSession;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class ShellWebSocketHandler extends AbstractWebSocketHandler {

    private static final int BUFFER_SIZE = 8 * 1024;

    private final SshSessionService ssh;
    private final SessionStateService state;

    private final Map<String, ClientSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, ChannelShell> shells = new ConcurrentHashMap<>();
    private final Map<String, OutputStream> stdins = new ConcurrentHashMap<>();
    private final Map<String, Thread> pumps = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession ws) throws Exception {
        String id = ws.getId();
        ClientSession sshSession = ssh.openSession();
        ChannelShell shell = ssh.openShell(sshSession);

        // Both directions use the channel's "inverted" streams instead of
        // setIn()/setOut(). openShell() already opened the channel, and MINA only
        // wires a setIn()/setOut() stream during open() — calling those setters
        // afterwards is a no-op for stdout (window never released) and for stdin
        // (no pump thread is ever started, so keystrokes never reach the PTY).
        // getInvertedIn() is the OutputStream that feeds the remote stdin;
        // getInvertedOut() is the InputStream we drain for remote stdout.
        OutputStream sshIn = shell.getInvertedIn();

        sessions.put(id, sshSession);
        shells.put(id, shell);
        stdins.put(id, sshIn);

        Thread pump = Thread.ofVirtual()
                .name("ws-stdout-pump-" + id)
                .start(() -> pumpStdout(id, ws, shell));
        pumps.put(id, pump);

        state.touch();
        log.info("WS shell opened for {}", id);
    }

    /**
     * Reads SSH stdout from the channel's inverted output stream and relays each
     * chunk to the WebSocket as a binary frame. Runs on its own virtual thread for
     * the lifetime of the shell; exits on stream EOF, a closed socket, or error.
     */
    private void pumpStdout(String id, WebSocketSession ws, ChannelShell shell) {
        String threadName = Thread.currentThread().getName();
        log.info("stdout pump started for {} on thread {}", id, threadName);
        String exitReason = "stream EOF";
        byte[] buf = new byte[BUFFER_SIZE];
        try (InputStream sshOut = shell.getInvertedOut()) {
            int n;
            while ((n = sshOut.read(buf)) != -1) {
                if (n == 0) {
                    continue;
                }
                log.info("stdout pump read {} bytes for {} (head={})", n, id, preview(buf, n));
                if (!ws.isOpen()) {
                    exitReason = "WebSocket closed";
                    break;
                }
                ws.sendMessage(new BinaryMessage(ByteBuffer.wrap(buf, 0, n)));
                log.info("WS sent {} bytes for {}", n, id);
            }
        } catch (IOException e) {
            exitReason = "IOException: " + e.getMessage();
        } catch (Exception e) {
            exitReason = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            log.info("stdout pump exited for {} on thread {} (reason: {})", id, threadName, exitReason);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession ws, BinaryMessage message) throws Exception {
        ByteBuffer payload = message.getPayload();
        byte[] data = new byte[payload.remaining()];
        payload.get(data);
        relayToStdin(ws.getId(), data);
    }

    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage message) throws Exception {
        relayToStdin(ws.getId(), message.getPayload().getBytes(StandardCharsets.UTF_8));
    }

    /** Writes an inbound WebSocket frame to the SSH channel's stdin. */
    private void relayToStdin(String id, byte[] data) throws IOException {
        log.info("WS frame received {} bytes for {} (head={})", data.length, id, preview(data, data.length));
        OutputStream sshIn = stdins.get(id);
        if (sshIn == null) {
            log.warn("dropping {} bytes for {} — no stdin (shell not open)", data.length, id);
            return;
        }
        sshIn.write(data);
        sshIn.flush();
        log.info("stdin wrote {} bytes to SSH for {}", data.length, id);
        state.touch();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        closeAll(ws.getId());
        log.info("WS shell closed for {} ({})", ws.getId(), status);
    }

    private void closeAll(String id) {
        Thread pump = pumps.remove(id);
        if (pump != null) {
            pump.interrupt();
        }
        OutputStream stdin = stdins.remove(id);
        if (stdin != null) {
            try { stdin.close(); } catch (IOException ignored) {}
        }
        ChannelShell shell = shells.remove(id);
        if (shell != null) {
            try { shell.close(true); } catch (Exception ignored) {}
        }
        ClientSession s = sessions.remove(id);
        if (s != null) {
            try { s.close(true); } catch (Exception ignored) {}
        }
    }

    /** Hex of the first few bytes of a chunk, for diagnosing the input/output cycle. */
    private static String preview(byte[] buf, int n) {
        int len = Math.min(n, 16);
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02x", buf[i] & 0xff));
        }
        return sb.toString();
    }
}
