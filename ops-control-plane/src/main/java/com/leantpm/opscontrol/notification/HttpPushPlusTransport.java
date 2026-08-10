package com.leantpm.opscontrol.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

public final class HttpPushPlusTransport implements PushPlusTransport {

    public static final URI OFFICIAL_ENDPOINT = URI.create("https://www.pushplus.plus/send");
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private final URI endpoint;
    private final HttpClient client;
    private final Duration timeout;
    private final ObjectMapper objectMapper;

    public HttpPushPlusTransport(Duration timeout) {
        this(
            OFFICIAL_ENDPOINT,
            HttpClient.newBuilder().connectTimeout(timeout).build(),
            timeout
        );
    }

    private HttpPushPlusTransport(URI endpoint, HttpClient client, Duration timeout) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.client = Objects.requireNonNull(client, "client");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.objectMapper = new ObjectMapper();
        if (!endpoint.isAbsolute() || timeout.isZero() || timeout.isNegative()
            || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("PushPlus endpoint and bounded timeout are required");
        }
    }

    static HttpPushPlusTransport forTests(URI endpoint, HttpClient client, Duration timeout) {
        return new HttpPushPlusTransport(endpoint, client, timeout);
    }

    @Override
    public PushPlusTransportResult send(PushPlusDeliveryRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("token", required(request.token(), "token"));
            body.put("title", required(request.title(), "title"));
            body.put("content", required(request.content(), "content"));
            body.put("template", required(request.template(), "template"));
            body.put("channel", required(request.channel(), "channel"));
            putOptional(body, "topic", request.topic());
            putOptional(body, "option", request.option());
            body.put("timestamp", request.timestamp());

            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body)))
                .build();
            HttpResponse<byte[]> response = client.send(
                httpRequest,
                HttpResponse.BodyHandlers.ofByteArray()
            );
            if (response.body().length > MAX_RESPONSE_BYTES) {
                return new PushPlusTransportResult(false, null, "PushPlus API 响应过大，未确认接受");
            }
            JsonNode responseJson = objectMapper.readTree(response.body());
            boolean accepted = response.statusCode() >= 200
                && response.statusCode() < 300
                && responseJson.path("code").asInt(Integer.MIN_VALUE) == 200;
            String requestId = accepted && responseJson.hasNonNull("data")
                ? bounded(responseJson.get("data").asText(), 128)
                : null;
            return accepted
                ? new PushPlusTransportResult(
                    true,
                    requestId,
                    "PushPlus API 已接受请求；这不代表消息已经送达终端"
                )
                : new PushPlusTransportResult(false, null, "PushPlus API 未接受请求");
        } catch (IOException exception) {
            throw new IllegalStateException("PushPlus API 请求无法安全完成", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PushPlus API 请求被中断", exception);
        }
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("PushPlus " + label + " is required");
        }
        return value;
    }

    private static void putOptional(ObjectNode body, String name, String value) {
        if (value != null && !value.isBlank()) {
            body.put(name, value);
        }
    }

    private static String bounded(String value, int maxLength) {
        return value == null || value.isBlank()
            ? null
            : value.substring(0, Math.min(value.length(), maxLength));
    }
}
