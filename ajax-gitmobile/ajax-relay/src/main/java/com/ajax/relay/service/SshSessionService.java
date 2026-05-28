package com.ajax.relay.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collection;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class SshSessionService {

    private final SshClient sshClient;

    @Value("${ssh.dev-host}")
    private String host;

    @Value("${ssh.dev-user}")
    private String user;

    @Value("${ssh.dev-port:22}")
    private int port;

    @Value("${ssh.private-key-path}")
    private String keyPath;

    @Value("${session.ssh-ready-timeout-seconds:120}")
    private long readyTimeoutSeconds;

    public ClientSession openSession() throws IOException {
        // TODO(security): AcceptAll trusts whatever host key the dev box presents.
        // Fine while we control both endpoints in one VPC; before exposing this to
        // others, pin the dev box's fingerprint with a KnownHostsServerKeyVerifier.
        sshClient.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        sshClient.setKeyIdentityProvider(new FileKeyPairProvider(Paths.get(keyPath)));

        ClientSession session = sshClient.connect(user, host, port)
                .verify(Duration.ofSeconds(readyTimeoutSeconds))
                .getSession();

        if (!session.auth().verify(Duration.ofSeconds(readyTimeoutSeconds)).isSuccess()) {
            session.close(true);
            throw new IOException("SSH auth failed to " + host);
        }
        return session;
    }

    public ChannelShell openShell(ClientSession session) throws IOException {
        ChannelShell shell = session.createShellChannel();
        shell.setPtyType("xterm-256color");
        shell.setPtyColumns(120);
        shell.setPtyLines(40);
        shell.open().verify(Duration.ofSeconds(30));
        return shell;
    }

    /**
     * Runs a one-shot command over a dedicated SSH <em>exec</em> channel (not a
     * PTY shell) and returns its raw stdout bytes. Because exec channels carry
     * no prompt, command echo, or terminal escapes, the output is exactly what
     * the program wrote — e.g. {@code gh repo list --json} yields pristine JSON.
     *
     * @throws IOException on a non-zero exit, a timeout, or a transport error.
     */
    public byte[] execCommand(String command, Duration timeout) throws IOException {
        try (ClientSession session = openSession()) {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            try (ChannelExec channel = session.createExecChannel(command)) {
                // Streams are wired before open(), so MINA pumps them correctly
                // (unlike the shell path, which must use the inverted streams).
                channel.setOut(stdout);
                channel.setErr(stderr);
                channel.open().verify(timeout);

                Collection<ClientChannelEvent> events =
                        channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), timeout);
                if (events.contains(ClientChannelEvent.TIMEOUT)) {
                    throw new IOException("exec timed out after " + timeout.toSeconds()
                            + "s: " + command);
                }

                Integer exit = channel.getExitStatus();
                if (exit == null || exit != 0) {
                    String err = stderr.toString(StandardCharsets.UTF_8).strip();
                    throw new IOException("exec exited with " + exit + ": " + command
                            + (err.isEmpty() ? "" : " — " + err));
                }
                return stdout.toByteArray();
            }
        }
    }

    public boolean waitForSsh(long timeoutSeconds) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        sshClient.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        while (System.currentTimeMillis() < deadline) {
            try (ClientSession s = sshClient.connect(user, host, port)
                    .verify(Duration.ofSeconds(5)).getSession()) {
                return true;
            } catch (Exception e) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }
}
