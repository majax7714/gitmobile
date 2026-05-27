package com.ajax.relay.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "session_state")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SessionState {

    @Id
    @Column(name = "instance_id")
    private String instanceId;

    @Column(name = "last_active")
    private Instant lastActive;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private InstanceStatus status;
}
