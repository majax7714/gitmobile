package com.ajax.relay.config;

import jakarta.annotation.PreDestroy;
import org.apache.sshd.client.SshClient;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.Security;

@Configuration
public class SshConfig {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private SshClient client;

    @Bean
    public SshClient sshClient() {
        client = SshClient.setUpDefaultClient();
        client.start();
        return client;
    }

    @PreDestroy
    public void shutdown() {
        if (client != null) {
            client.stop();
        }
    }
}
