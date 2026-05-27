package com.ajax.relay.service;

import com.ajax.relay.model.InstanceStatus;
import com.ajax.relay.model.SessionState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class IdleMonitorService {

    private final Ec2Service ec2;
    private final SessionStateService state;

    @Value("${session.idle-threshold-minutes}")
    private long idleThresholdMinutes;

    @Scheduled(fixedDelayString = "${session.idle-check-interval-seconds}000")
    public void checkIdle() {
        SessionState s = state.current().orElse(null);
        if (s == null || s.getLastActive() == null) {
            return;
        }
        if (s.getStatus() != InstanceStatus.RUNNING) {
            return;
        }
        Duration since = Duration.between(s.getLastActive(), Instant.now());
        if (since.toMinutes() >= idleThresholdMinutes) {
            log.info("Idle {} min >= threshold {} — stopping {}",
                    since.toMinutes(), idleThresholdMinutes, ec2.instanceId());
            ec2.stop();
            state.updateStatus(InstanceStatus.STOPPING);
        }
    }
}
