package com.ajax.relay.service;

import com.ajax.relay.model.InstanceStatus;
import com.ajax.relay.model.SessionState;
import com.ajax.relay.repository.SessionStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SessionStateService {

    @Value("${aws.dev-instance-id}")
    private String instanceId;

    private final SessionStateRepository repo;

    @Transactional
    public SessionState touch() {
        SessionState s = repo.findById(instanceId).orElseGet(this::initial);
        s.setLastActive(Instant.now());
        return repo.save(s);
    }

    @Transactional
    public SessionState updateStatus(InstanceStatus status) {
        SessionState s = repo.findById(instanceId).orElseGet(this::initial);
        s.setStatus(status);
        return repo.save(s);
    }

    @Transactional(readOnly = true)
    public Optional<SessionState> current() {
        return repo.findById(instanceId);
    }

    private SessionState initial() {
        return SessionState.builder()
                .instanceId(instanceId)
                .lastActive(Instant.now())
                .status(InstanceStatus.STOPPED)
                .build();
    }
}
