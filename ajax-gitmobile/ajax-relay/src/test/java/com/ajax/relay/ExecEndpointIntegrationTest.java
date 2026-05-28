package com.ajax.relay;

import com.ajax.relay.service.Ec2Service;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves POST /api/exec runs a command over a real MINA exec channel and
 * unwraps its stdout cleanly. An in-process SSH server answers any exec request
 * with known JSON and exit 0, so we assert the response body carries that JSON
 * verbatim — no PTY noise, no sanitization needed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class ExecEndpointIntegrationTest {

    private static final String REPO_JSON =
            "[{\"name\":\"alpha\",\"description\":\"first\",\"updatedAt\":\"2026-01-01T00:00:00Z\",\"isPrivate\":false}]";

    static SshServer sshd;

    @Autowired
    MockMvc mvc;

    // The exec path never touches AWS; mock it so the context needs no creds.
    @MockBean
    Ec2Service ec2;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);

        KeyPair clientKey = kpg.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(clientKey.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        Path keyFile = Files.createTempFile("relay-exec-itest-key", ".pem");
        Files.writeString(keyFile, pem);

        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(KeyPairProvider.wrap(kpg.generateKeyPair()));
        sshd.setPublickeyAuthenticator((user, key, session) -> true);
        // Any exec request gets the canned JSON and a clean exit 0.
        sshd.setCommandFactory((channel, command) -> new JsonExecCommand());
        sshd.start();

        Path db = Files.createTempFile("relay-exec-itest", ".db");
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
    void execReturnsUnwrappedJsonStdout() throws Exception {
        mvc.perform(post("/api/exec")
                        .header("Authorization", "Bearer itest-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cmd\":\"gh repo list --json name,description,updatedAt,isPrivate --limit 100\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exitCode").value(0))
                .andExpect(jsonPath("$.stdout").value(REPO_JSON));
    }

    @Test
    void execRejectsCommandOutsideAllowlist() throws Exception {
        mvc.perform(post("/api/exec")
                        .header("Authorization", "Bearer itest-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cmd\":\"rm -rf /\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void execRejectsChainedCommand() throws Exception {
        mvc.perform(post("/api/exec")
                        .header("Authorization", "Bearer itest-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cmd\":\"gh repo list; rm -rf ~\"}"))
                .andExpect(status().isForbidden());
    }

    /** Server-side exec command: writes the canned JSON to stdout and exits 0. */
    static class JsonExecCommand implements Command {
        private OutputStream out;
        private ExitCallback callback;

        @Override public void setInputStream(InputStream in) { /* exec has no stdin here */ }
        @Override public void setOutputStream(OutputStream out) { this.out = out; }
        @Override public void setErrorStream(OutputStream err) { /* unused */ }
        @Override public void setExitCallback(ExitCallback callback) { this.callback = callback; }

        @Override
        public void start(ChannelSession channel, Environment env) throws IOException {
            out.write(REPO_JSON.getBytes(StandardCharsets.UTF_8));
            out.flush();
            callback.onExit(0);
        }

        @Override
        public void destroy(ChannelSession channel) { /* nothing to clean up */ }
    }
}
