package com.leantpm.opscontrol.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpPushPlusTransportTest {

    @Test
    void postsOfficialJsonShapeAndTreatsCode200AsAcceptedNotDelivered() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/send", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"code\":200,\"msg\":\"请求成功\",\"data\":\"request-001\"}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            URI endpoint = URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/send"
            );
            HttpPushPlusTransport transport = HttpPushPlusTransport.forTests(
                endpoint,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                Duration.ofSeconds(2)
            );

            PushPlusTransportResult result = transport.send(new PushPlusDeliveryRequest(
                "owner",
                "token-owner-1234567890",
                "[严重] LeanTPM 服务异常",
                "## 当前状态\nDOWN",
                "markdown",
                "wechat",
                null,
                null,
                1786323600000L
            ));

            assertThat(result.accepted()).isTrue();
            assertThat(result.requestId()).isEqualTo("request-001");
            assertThat(result.summary()).contains("API 已接受").doesNotContain("已送达");
            assertThat(body.get())
                .contains("\"token\":\"token-owner-1234567890\"")
                .contains("\"template\":\"markdown\"")
                .contains("\"channel\":\"wechat\"")
                .contains("\"timestamp\":1786323600000");
        } finally {
            server.stop(0);
        }
    }
}
