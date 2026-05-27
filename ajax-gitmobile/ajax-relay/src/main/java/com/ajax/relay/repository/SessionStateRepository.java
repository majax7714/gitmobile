package com.ajax.relay.repository;

import com.ajax.relay.model.SessionState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SessionStateRepository extends JpaRepository<SessionState, String> {
}
