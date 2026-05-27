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
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class ShellWebSocketHandler extends AbstractWebSocketHandler {

    private final SshSessionService ssh;
    private final SessionStateService state;

    private final Map<String, ClientSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, ChannelShell> shells = new ConcurrentHashMap<>();
    private final Map<String, PipedOutputStream> stdins = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession ws) throws Exception {
        ClientSession sshSession = ssh.openSession();
        ChannelShell shell = ssh.openShell(sshSession);

        PipedOutputStream stdinSink = new PipedOutputStream();
        PipedInputStream stdinSource = new PipedInputStream(stdinSink, 64 * 1024);
        shell.setIn(stdinSource);

        OutputStream stdoutSink = new WebSocketOutputStream(ws);
        shell.setOut(stdoutSink);
        shell.setErr(stdoutSink);

        sessions.put(ws.getId(), sshSession);
        shells.put(ws.getId(), shell);
        stdins.put(ws.getId(), stdinSink);

        state.touch();
        log.info("WS shell opened for {}", ws.getId());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession ws, BinaryMessage message) throws Exception {
        PipedOutputStream stdin = stdins.get(ws.getId());
        if (stdin == null) {
            return;
        }
        ByteBuffer buf = message.getPayload();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        stdin.write(data);
        stdin.flush();
        state.touch();
    }

    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage message) throws Exception {
        PipedOutputStream stdin = stdins.get(ws.getId());
        if (stdin == null) {
            return;
        }
        stdin.write(message.getPayload().getBytes());
        stdin.flush();
        state.touch();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        closeAll(ws.getId());
        log.info("WS shell closed for {} ({})", ws.getId(), status);
    }

    private void closeAll(String id) {
        PipedOutputStream stdin = stdins.remove(id);
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

    static class WebSocketOutputStream extends OutputStream {
        private final WebSocketSession ws;

        WebSocketOutputStream(WebSocketSession ws) {
            this.ws = ws;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            if (!ws.isOpen()) {
                return;
            }
            ws.sendMessage(new BinaryMessage(ByteBuffer.wrap(b, off, len)));
        }
    }
}
