package com.ajax.relay.controller;

import com.ajax.relay.model.InstanceStatus;
import com.ajax.relay.model.SessionState;
import com.ajax.relay.service.Ec2Service;
import com.ajax.relay.service.SessionStateService;
import com.ajax.relay.service.SshSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/session")
@RequiredArgsConstructor
@Slf4j
public class SessionController {

    private final Ec2Service ec2;
    private final SshSessionService ssh;
    private final SessionStateService state;

    @Value("${session.ssh-ready-timeout-seconds:120}")
    private long sshReadyTimeoutSeconds;

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start() {
        InstanceStatus current = ec2.describe();
        state.touch();
        if (current == InstanceStatus.RUNNING) {
            state.updateStatus(InstanceStatus.RUNNING);
            return ResponseEntity.ok(Map.of(
                    "status", InstanceStatus.RUNNING,
                    "instanceId", ec2.instanceId(),
                    "message", "already running"));
        }
        if (current == InstanceStatus.STOPPED) {
            ec2.start();
            state.updateStatus(InstanceStatus.STARTING);
            Thread.ofVirtual().name("ssh-ready-watch").start(() -> {
                boolean ready = ssh.waitForSsh(sshReadyTimeoutSeconds);
                if (ready) {
                    state.updateStatus(InstanceStatus.RUNNING);
                    log.info("SSH ready on {}", ec2.instanceId());
                } else {
                    log.warn("SSH not ready within {}s on {}", sshReadyTimeoutSeconds, ec2.instanceId());
                }
            });
        }
        return ResponseEntity.accepted().body(Map.of(
                "status", InstanceStatus.STARTING,
                "instanceId", ec2.instanceId(),
                "message", "start initiated"));
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stop() {
        ec2.stop();
        state.updateStatus(InstanceStatus.STOPPING);
        return ResponseEntity.accepted().body(Map.of(
                "status", InstanceStatus.STOPPING,
                "instanceId", ec2.instanceId()));
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Map<String, Object>> heartbeat() {
        SessionState s = state.touch();
        return ResponseEntity.ok(Map.of(
                "lastActive", s.getLastActive().toString(),
                "instanceId", s.getInstanceId()));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        InstanceStatus aws = ec2.describe();
        SessionState s = state.current().orElse(null);
        return ResponseEntity.ok(Map.of(
                "instanceId", ec2.instanceId(),
                "awsStatus", aws,
                "trackedStatus", s == null ? "UNKNOWN" : s.getStatus(),
                "lastActive", s == null || s.getLastActive() == null ? "" : s.getLastActive().toString(),
                "now", Instant.now().toString()));
    }
}
