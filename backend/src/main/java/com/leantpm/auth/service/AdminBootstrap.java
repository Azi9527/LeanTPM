package com.leantpm.auth.service;

import com.leantpm.auth.mapper.AuthMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AuthMapper authMapper;
    private final PasswordEncoder passwordEncoder;
    private final String adminPassword;

    public AdminBootstrap(
            AuthMapper authMapper,
            PasswordEncoder passwordEncoder,
            @Value("${leantpm.bootstrap.admin-password:}") String adminPassword
    ) {
        this.authMapper = authMapper;
        this.passwordEncoder = passwordEncoder;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (authMapper.countUsers(AuthService.DEFAULT_TENANT_ID) > 0) {
            return;
        }
        if (adminPassword == null || adminPassword.isBlank()) {
            log.warn("系统暂无用户，未设置 LEANTPM_BOOTSTRAP_ADMIN_PASSWORD，已跳过管理员初始化");
            return;
        }
        if (adminPassword.length() < 6) {
            throw new IllegalStateException("LEANTPM_BOOTSTRAP_ADMIN_PASSWORD 长度不能少于 6 位");
        }
        authMapper.insertBootstrapAdmin(
                AuthService.DEFAULT_TENANT_ID,
                "admin",
                "系统管理员",
                passwordEncoder.encode(adminPassword)
        );
        var admin = authMapper.findByUsername(AuthService.DEFAULT_TENANT_ID, "admin");
        authMapper.assignRole(AuthService.DEFAULT_TENANT_ID, admin.getId(), 1L);
        log.info("已初始化 admin 管理员，请在首次登录后立即修改密码");
    }
}
