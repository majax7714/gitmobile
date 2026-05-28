package com.ajax.relay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Auth is handled entirely by SecurityConfig's bearer-token filter; exclude the
// default UserDetailsService so Spring stops generating (and logging) a random
// password on every boot.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class RelayApplication {
    public static void main(String[] args) {
        SpringApplication.run(RelayApplication.class, args);
    }
}
