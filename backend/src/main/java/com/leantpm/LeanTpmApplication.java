package com.leantpm;

import com.leantpm.security.JwtProperties;
import com.leantpm.system.attachment.StorageProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@MapperScan({
        "com.leantpm.auth.mapper",
        "com.leantpm.system.mapper",
        "com.leantpm.foundation.mapper",
        "com.leantpm.security.datascope"
})
@EnableConfigurationProperties({JwtProperties.class, StorageProperties.class})
public class LeanTpmApplication {

    public static void main(String[] args) {
        SpringApplication.run(LeanTpmApplication.class, args);
    }
}
