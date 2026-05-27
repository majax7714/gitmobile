package com.ajax.relay;

import com.ajax.relay.controller.SessionController;
import com.ajax.relay.model.InstanceStatus;
import com.ajax.relay.model.SessionState;
import com.ajax.relay.service.Ec2Service;
import com.ajax.relay.service.SessionStateService;
import com.ajax.relay.service.SshSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SessionControllerTest {

    private Ec2Service ec2;
    private SshSessionService ssh;
    private SessionStateService state;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ec2 = mock(Ec2Service.class);
        ssh = mock(SshSessionService.class);
        state = mock(SessionStateService.class);
        when(ec2.instanceId()).thenReturn("i-test");
        SessionController controller = new SessionController(ec2, ssh, state);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void startReturnsRunningWhenAlreadyUp() throws Exception {
        when(ec2.describe()).thenReturn(InstanceStatus.RUNNING);
        when(state.touch()).thenReturn(SessionState.builder().instanceId("i-test").lastActive(Instant.now()).status(InstanceStatus.RUNNING).build());
        mvc.perform(post("/api/session/start").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void startAcceptsWhenStopped() throws Exception {
        when(ec2.describe()).thenReturn(InstanceStatus.STOPPED);
        when(state.touch()).thenReturn(SessionState.builder().instanceId("i-test").lastActive(Instant.now()).status(InstanceStatus.STOPPED).build());
        mvc.perform(post("/api/session/start"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("STARTING"));
    }

    @Test
    void heartbeatTouches() throws Exception {
        when(state.touch()).thenReturn(SessionState.builder()
                .instanceId("i-test")
                .lastActive(Instant.parse("2026-01-01T00:00:00Z"))
                .status(InstanceStatus.RUNNING)
                .build());
        mvc.perform(post("/api/session/heartbeat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instanceId").value("i-test"));
    }

    @Test
    void statusReturnsBoth() throws Exception {
        when(ec2.describe()).thenReturn(InstanceStatus.RUNNING);
        when(state.current()).thenReturn(Optional.of(SessionState.builder()
                .instanceId("i-test")
                .lastActive(Instant.parse("2026-01-01T00:00:00Z"))
                .status(InstanceStatus.RUNNING)
                .build()));
        mvc.perform(get("/api/session/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awsStatus").value("RUNNING"))
                .andExpect(jsonPath("$.trackedStatus").value("RUNNING"));
    }

    @Test
    void stopReturnsAccepted() throws Exception {
        mvc.perform(post("/api/session/stop"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("STOPPING"));
    }
}
