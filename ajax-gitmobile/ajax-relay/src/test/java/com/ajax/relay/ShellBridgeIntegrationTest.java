package com.ajax.relay;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ajax.relay.controller.ShellWebSocketHandler;
import com.ajax.relay.service.Ec2Service;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.shell.ShellFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof that the shell bridge plumbs both directions: a WebSocket
 * binary frame must reach the SSH server's stdin, and the server's stdout must
 * come back to the WebSocket client. Runs the full Spring context against an
 * in-process MINA SSH server with a deterministic echo shell.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShellBridgeIntegrationTest {

    private static final String BANNER = "relay-test-banner";
    private static final String RESPONSE = "ec2-user";

    static SshServer sshd;
    static final ByteArrayOutputStream serverStdin = new ByteArrayOutputStream();
    static final CountDownLatch serverGotInput = new CountDownLatch(1);

    @LocalServerPort
    int port;

    // The WebSocket path never touches AWS; mock it so the context needs no creds.
    @MockBean
    Ec2Service ec2;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);

        // The relay authenticates with a private key file; write one the server
        // will accept (the server's authenticator below trusts any key).
        KeyPair clientKey = kpg.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(clientKey.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        Path keyFile = Files.createTempFile("relay-itest-key", ".pem");
        Files.writeString(keyFile, pem);

        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(KeyPairProvider.wrap(kpg.generateKeyPair()));
        sshd.setPublickeyAuthenticator((user, key, session) -> true);
        sshd.setShellFactory(new EchoShellFactory());
        sshd.start();

        Path db = Files.createTempFile("relay-itest", ".db");
        registry.add("ssh.dev-host", () -> "127.0.0.1");
        registry.add("ssh.dev-port", sshd::getPort);
        registry.add("ssh.dev-user", () -> "ec2-user");
        registry.add("ssh.private-key-path", keyFile::toString);
        registry.add("auth.bearer-token", () -> "itest-token");
        registry.add("session.idle-check-interval-seconds", () -> "3600");
        registry.add("session.idle-threshold-minutes", () -> "999");
        registry.add("session.ssh-ready-timeout-seconds", () -> "10");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + db);
    }

    @AfterAll
    static void stopServer() throws IOException {
        if (sshd != null) {
            sshd.stop(true);
        }
    }

    @Test
    void stdinReachesSshAndStdoutReturnsToWebSocket() throws Exception {
        Logger handlerLog = (Logger) LoggerFactory.getLogger(ShellWebSocketHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.list = Collections.synchronizedList(new ArrayList<>());
        appender.start();
        handlerLog.addAppender(appender);

        StringBuilder fromServer = new StringBuilder();
        CountDownLatch bannerReceived = new CountDownLatch(1);
        CountDownLatch responseReceived = new CountDownLatch(1);

        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.execute(new BinaryWebSocketHandler() {
            @Override
            protected void handleBinaryMessage(WebSocketSession s, BinaryMessage message) {
                byte[] data = new byte[message.getPayload().remaining()];
                message.getPayload().get(data);
                synchronized (fromServer) {
                    fromServer.append(new String(data, StandardCharsets.UTF_8));
                    if (fromServer.indexOf(BANNER) >= 0) {
                        bannerReceived.countDown();
                    }
                    if (fromServer.indexOf(RESPONSE) >= 0) {
                        responseReceived.countDown();
                    }
                }
            }
        }, "ws://127.0.0.1:" + port + "/api/shell?token=itest-token").get(5, TimeUnit.SECONDS);

        try {
            // Wait for the banner so we know the SSH shell + stdout pump are live
            // before we send input (avoids racing afterConnectionEstablished).
            assertTrue(bannerReceived.await(5, TimeUnit.SECONDS), "never received the initial banner");

            session.sendMessage(new BinaryMessage("whoami\n".getBytes(StandardCharsets.UTF_8)));

            // (a) the frame reached the SSH server's stdin
            assertTrue(serverGotInput.await(2, TimeUnit.SECONDS), "SSH server never received stdin");
            synchronized (serverStdin) {
                assertThat(serverStdin.toString(StandardCharsets.UTF_8)).contains("whoami");
            }

            // (b) the server's response came back to the WebSocket
            assertTrue(responseReceived.await(2, TimeUnit.SECONDS), "WebSocket never received the response");
            synchronized (fromServer) {
                assertThat(fromServer.toString()).contains("whoami").contains(RESPONSE);
            }
        } finally {
            session.close(CloseStatus.NORMAL);
        }

        // (c) clean close logs the pump exit
        awaitLog(appender, "stdout pump exited", 3000);
    }

    private static void awaitLog(ListAppender<ILoggingEvent> appender, String needle, long millis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            synchronized (appender.list) {
                boolean found = appender.list.stream()
                        .anyMatch(e -> e.getFormattedMessage().contains(needle));
                if (found) {
                    return;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("expected log line containing: " + needle);
    }

    /** Server-side shell that echoes stdin and answers a newline with "ec2-user". */
    static class EchoShellFactory implements ShellFactory {
        @Override
        public Command createShell(ChannelSession channel) {
            return new EchoCommand();
        }
    }

    static class EchoCommand implements Command {
        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback callback;
        private Thread thread;

        @Override public void setInputStream(InputStream in) { this.in = in; }
        @Override public void setOutputStream(OutputStream out) { this.out = out; }
        @Override public void setErrorStream(OutputStream err) { this.err = err; }
        @Override public void setExitCallback(ExitCallback callback) { this.callback = callback; }

        @Override
        public void start(ChannelSession channel, Environment env) {
            thread = new Thread(this::run, "test-echo-shell");
            thread.setDaemon(true);
            thread.start();
        }

        private void run() {
            try {
                out.write((BANNER + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (n == 0) {
                        continue;
                    }
                    synchronized (serverStdin) {
                        serverStdin.write(buf, 0, n);
                    }
                    serverGotInput.countDown();
                    out.write(buf, 0, n); // echo, like a PTY
                    out.flush();
                    boolean newline = false;
                    for (int i = 0; i < n; i++) {
                        if (buf[i] == '\n') {
                            newline = true;
                            break;
                        }
                    }
                    if (newline) {
                        out.write((RESPONSE + "\r\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                }
            } catch (IOException ignored) {
                // client closed; fall through to exit
            } finally {
                if (callback != null) {
                    callback.onExit(0);
                }
            }
        }

        @Override
        public void destroy(ChannelSession channel) {
            if (thread != null) {
                thread.interrupt();
            }
        }
    }
}
