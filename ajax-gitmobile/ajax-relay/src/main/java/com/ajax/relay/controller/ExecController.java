package com.ajax.relay.controller;

import com.ajax.relay.service.SessionStateService;
import com.ajax.relay.service.SshSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One-shot command execution over a dedicated SSH exec channel, so the Repos
 * tab gets pristine program output (no PTY prompt/echo/escapes to scrape).
 *
 * <p>The allowlist is the security boundary: this endpoint runs commands on the
 * dev box, so without it this is arbitrary remote code execution. Each command
 * must (a) match an allowed prefix and (b) contain no shell metacharacters, so a
 * prefix match cannot be extended into a chained/redirected command.
 */
@RestController
@RequestMapping("/api/exec")
@RequiredArgsConstructor
@Slf4j
public class ExecController {

    private final SshSessionService ssh;
    private final SessionStateService state;

    private static final Duration EXEC_TIMEOUT = Duration.ofSeconds(20);

    /** Exact prefixes the client is allowed to run (day-one set). */
    private static final List<String> ALLOWED_PREFIXES = List.of(
            "gh repo list",
            "ls -la ~/repos",
            "ls ~/repos");

    /** Shell metacharacters that could chain/redirect past an allowed prefix. */
    private static final Pattern FORBIDDEN = Pattern.compile("[;&|<>`$\\n\\r\\\\]");

    @PostMapping
    public ResponseEntity<Map<String, Object>> exec(@RequestBody Map<String, String> body) {
        String cmd = body == null ? null : body.get("cmd");
        if (cmd == null || cmd.isBlank()) {
            return ResponseEntity.badRequest().body(Map.<String, Object>of("error", "missing 'cmd'"));
        }
        String trimmed = cmd.strip();

        if (FORBIDDEN.matcher(trimmed).find()
                || ALLOWED_PREFIXES.stream().noneMatch(trimmed::startsWith)) {
            log.warn("rejected exec command: {}", trimmed);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.<String, Object>of("error", "command not allowed"));
        }

        try {
            byte[] out = ssh.execCommand(trimmed, EXEC_TIMEOUT);
            state.touch();
            return ResponseEntity.ok(Map.<String, Object>of(
                    "stdout", new String(out, StandardCharsets.UTF_8),
                    "exitCode", 0));
        } catch (Exception e) {
            log.warn("exec failed for '{}': {}", trimmed, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.<String, Object>of("error", e.getMessage() == null ? "exec failed" : e.getMessage()));
        }
    }
}
