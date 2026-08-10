package com.leantpm.common.openapi;

import com.leantpm.auth.controller.AuthController;
import com.leantpm.common.idempotency.Idempotent;
import com.leantpm.foundation.controller.BrandingController;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

@Configuration
public class OpenApiConfig {
    public static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI leanTpmOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("LeanTPM API")
                        .version("v1")
                        .description(
                                "精益设备管理系统 REST API。所有响应使用统一 code/message/data/timestamp 结构。"
                        )
                        .contact(new Contact().name("LeanTPM")))
                .servers(List.of(new Server().url("/").description("当前服务")))
                .components(new Components().addSecuritySchemes(
                        BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                ));
    }

    @Bean
    public OperationCustomizer standardOperationCustomizer() {
        return (operation, handlerMethod) -> {
            boolean publicAuthOperation =
                    AuthController.class.isAssignableFrom(handlerMethod.getBeanType())
                            && Set.of("login", "refresh")
                            .contains(handlerMethod.getMethod().getName());
            boolean publicBrandingOperation =
                    BrandingController.class.isAssignableFrom(handlerMethod.getBeanType())
                            && "settings".equals(handlerMethod.getMethod().getName());
            if (!publicAuthOperation && !publicBrandingOperation) {
                operation.addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
            }
            if (handlerMethod.hasMethodAnnotation(Idempotent.class)) {
                operation.addParametersItem(new HeaderParameter()
                        .name("Idempotency-Key")
                        .required(true)
                        .description("8～128 位请求幂等键；重试同一请求时必须复用")
                        .example("web-550e8400-e29b-41d4-a716-446655440000"));
            }
            addResponse(operation, "400", "请求参数、幂等键或业务规则无效");
            addResponse(operation, "401", "未登录、令牌过期或会话失效");
            addResponse(operation, "403", "功能权限或数据范围不足");
            addResponse(operation, "409", "乐观锁冲突、重复资源或幂等冲突");
            addResponse(operation, "500", "服务器内部错误");
            addResponse(operation, "503", "必要的持久化依赖暂不可用");
            return operation;
        };
    }

    private void addResponse(io.swagger.v3.oas.models.Operation operation, String code, String description) {
        if (!operation.getResponses().containsKey(code)) {
            operation.getResponses().addApiResponse(code, new ApiResponse().description(description));
        }
    }
}
