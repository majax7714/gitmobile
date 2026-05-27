package com.ajax.relay;

import com.ajax.relay.config.SecurityConfig;
import com.ajax.relay.controller.SessionController;
import com.ajax.relay.service.Ec2Service;
import com.ajax.relay.service.SessionStateService;
import com.ajax.relay.service.SshSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real security filter chain (CORS + bearer filter), unlike
 * {@link SessionControllerTest} which uses standaloneSetup and bypasses filters.
 */
@WebMvcTest(SessionController.class)
@Import(SecurityConfig.class)
class CorsConfigTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private Ec2Service ec2;
    @MockBean
    private SshSessionService ssh;
    @MockBean
    private SessionStateService state;

    @Test
    void preflightFromCapacitorOriginReturns200WithAllowOrigin() throws Exception {
        mvc.perform(options("/api/session/status")
                        .header("Origin", "capacitor://localhost")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "capacitor://localhost"));
    }
}
