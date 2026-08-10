package com.leantpm.opscontrol;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class OpsControlPlaneApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpsControlPlaneApplication.class, args);
    }
}
