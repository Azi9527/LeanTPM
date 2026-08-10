package com.leantpm.opscontrol.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OperationsDashboardStaticContractTest {

    @Test
    void showsEveryOperationsDomainBeforeLiveMonitoringIsConnected() throws IOException {
        String html = readClasspathResource("/static/index.html");

        assertThat(html)
            .contains("data-operations-placeholder=\"system\"")
            .contains("data-operations-placeholder=\"services\"")
            .contains("data-operations-placeholder=\"database\"")
            .contains("data-operations-placeholder=\"logs\"")
            .contains("服务器资源")
            .contains("固定服务")
            .contains("数据库")
            .contains("日志")
            .contains("待接入")
            .contains("不代表云服务器实时数据");
    }

    @Test
    void rendersPercentageMetricsAsAccessibleCharts() throws IOException {
        String script = readClasspathResource("/static/app.js");
        String styles = readClasspathResource("/static/styles.css");

        assertThat(script)
            .contains("cpuUsedPercent")
            .contains("systemMemoryUsedPercent")
            .contains("diskUsedPercent")
            .contains("jvmHeapUsedPercent")
            .contains("metric-bar")
            .contains("aria-label")
            .contains("style.width");
        assertThat(styles)
            .contains(".metric-bar")
            .contains(".metric-bar-fill");
    }

    @Test
    void authenticatesOperationsFirstAndCacheBustsBrowserAssets() throws IOException {
        String html = readClasspathResource("/static/index.html");
        String script = readClasspathResource("/static/app.js");

        assertThat(html)
            .contains("/release-tracker.js?v=20260810-host-metrics")
            .contains("/app.js?v=20260810-host-metrics");
        assertThat(script)
            .contains("正在验证运维身份")
            .contains("const dashboard = await api(\"/api/v1/operations/status\")")
            .contains("renderOperations(dashboard)")
            .contains("Promise.allSettled");
    }

    private String readClasspathResource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as("classpath resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
