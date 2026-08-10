package com.leantpm.opscontrol.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class HttpReadinessProbe implements OperationsProbe {

    private final URI endpoint;
    private final Duration timeout;
    private final HttpClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HttpReadinessProbe(URI endpoint, Duration timeout, HttpClient client) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.client = Objects.requireNonNull(client, "client");
        if (!"http".equalsIgnoreCase(endpoint.getScheme())
            || endpoint.getHost() == null
            || !isLoopbackLiteral(endpoint.getHost())
            || endpoint.getPort() != 18080
            || !"/actuator/health/readiness".equals(endpoint.getPath())
            || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(15)) > 0) {
            throw new IllegalArgumentException("backend readiness endpoint must be fixed loopback port 18080");
        }
    }

    @Override
    public String id() {
        return "backend-readiness";
    }

    @Override
    public List<OperationsComponent> observe(Instant observedAt) {
        OperationsHealth status = OperationsHealth.DOWN;
        String summary = "Backend readiness 检查失败";
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout).GET().build();
            HttpResponse<byte[]> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofByteArray()
            );
            if (response.body().length <= 16 * 1024
                && response.statusCode() == 200
                && "UP".equals(objectMapper.readTree(response.body()).path("status").asText())) {
                status = OperationsHealth.HEALTHY;
                summary = "Backend readiness=UP";
            }
        } catch (IOException exception) {
            summary = "Backend readiness 无法连接";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            summary = "Backend readiness 检查被中断";
        }
        return List.of(new OperationsComponent(
            "service:backend-readiness", "Backend API 就绪状态",
            OperationsComponentKind.SERVICE, status, summary, observedAt,
            Map.of("endpoint", "127.0.0.1:18080"), null
        ));
    }

    private static boolean isLoopbackLiteral(String host) {
        return "127.0.0.1".equals(host) || "::1".equals(host)
            || "[::1]".equals(host);
    }
}
