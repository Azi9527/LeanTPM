package com.leantpm;

import com.leantpm.security.JwtProperties;
import com.leantpm.common.idempotency.IdempotencyProperties;
import com.leantpm.system.attachment.StorageProperties;
import org.apache.ibatis.annotations.Mapper;
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
        "com.leantpm.masterdata",
        "com.leantpm.equipment",
        "com.leantpm.inspection",
        "com.leantpm.maintenance",
        "com.leantpm.oee",
        "com.leantpm.visualization",
        "com.leantpm.mobile",
        "com.leantpm.security.datascope",
        "com.leantpm.security.session.mapper"
})
@MapperScan(
        value = "com.leantpm.common.idempotency",
        annotationClass = Mapper.class
)
@EnableConfigurationProperties({
        JwtProperties.class,
        StorageProperties.class,
        IdempotencyProperties.class
})
public class LeanTpmApplication {

    public static void main(String[] args) {
        SpringApplication.run(LeanTpmApplication.class, args);
    }
}
