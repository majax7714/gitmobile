package com.ajax.relay.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Paths;
import java.time.Duration;
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

    @Value("${ssh.private-key-path}")
    private String keyPath;

    @Value("${session.ssh-ready-timeout-seconds:120}")
    private long readyTimeoutSeconds;

    public ClientSession openSession() throws IOException {
        sshClient.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        sshClient.setKeyIdentityProvider(new FileKeyPairProvider(Paths.get(keyPath)));

        ClientSession session = sshClient.connect(user, host, 22)
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

    public boolean waitForSsh(long timeoutSeconds) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        sshClient.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        while (System.currentTimeMillis() < deadline) {
            try (ClientSession s = sshClient.connect(user, host, 22)
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
