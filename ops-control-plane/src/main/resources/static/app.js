"use strict";

const state = {
  token: "",
  selected: null,
  plan: null,
  operationsTimer: null
};

const {
  createReleaseTracker,
  isReleaseTerminal,
  shouldTrackRelease
} = globalThis.LeanTpmReleaseTracker;

const releaseStateLabels = Object.freeze({
  VERIFIED: "发布包已验证",
  AWAITING_CONFIRMATION: "等待确认",
  QUEUED: "已排队，等待服务器复核",
  AGENT_VERIFIED: "服务器已复核，尚未部署",
  DEPLOYED: "生产部署成功",
  FAILED: "失败"
});

const elements = {
  authForm: document.querySelector("#authForm"),
  authSubmitButton: document.querySelector("#authSubmitButton"),
  authState: document.querySelector("#authState"),
  token: document.querySelector("#token"),
  uploadForm: document.querySelector("#uploadForm"),
  deploymentBundle: document.querySelector("#deploymentBundle"),
  selectedRelease: document.querySelector("#selectedRelease"),
  planButton: document.querySelector("#planButton"),
  planDetails: document.querySelector("#planDetails"),
  confirmForm: document.querySelector("#confirmForm"),
  reason: document.querySelector("#reason"),
  refreshButton: document.querySelector("#refreshButton"),
  releaseList: document.querySelector("#releaseList"),
  auditRefreshButton: document.querySelector("#auditRefreshButton"),
  auditList: document.querySelector("#auditList"),
  operationsBadge: document.querySelector("#operationsBadge"),
  operationsSummary: document.querySelector("#operationsSummary"),
  operationsObservedAt: document.querySelector("#operationsObservedAt"),
  operationsList: document.querySelector("#operationsList"),
  remediationList: document.querySelector("#remediationList"),
  notificationState: document.querySelector("#notificationState"),
  operationsRefreshButton: document.querySelector("#operationsRefreshButton"),
  healthBadge: document.querySelector("#healthBadge"),
  agentBadge: document.querySelector("#agentBadge"),
  trackingBadge: document.querySelector("#trackingBadge"),
  progressSummary: document.querySelector("#progressSummary"),
  progressSteps: Array.from(document.querySelectorAll("[data-progress-step]")),
  message: document.querySelector("#message")
};

const releaseProgressOrder = Object.freeze([
  "VERIFIED",
  "AWAITING_CONFIRMATION",
  "QUEUED",
  "AGENT_VERIFIED",
  "DEPLOYED"
]);

function idempotencyKey(prefix) {
  return `${prefix}-${crypto.randomUUID()}`;
}

function showMessage(text, ok = false) {
  elements.message.textContent = text;
  elements.message.className = ok ? "message ok" : "message";
}

function releaseStateLabel(value) {
  return releaseStateLabels[value] || value || "未知状态";
}

function renderProgress(release) {
  if (!release) {
    elements.trackingBadge.textContent = "尚未选择发布";
    elements.trackingBadge.className = "badge neutral";
    elements.progressSummary.textContent = "上传并选择部署授权包后，这里会自动跟踪发布结果。";
    for (const step of elements.progressSteps) {
      step.className = "";
      step.querySelector("span").textContent = "等待";
    }
    return;
  }

  const currentIndex = releaseProgressOrder.indexOf(release.state);
  const failed = release.state === "FAILED";
  elements.trackingBadge.textContent = releaseStateLabel(release.state);
  elements.trackingBadge.className = release.state === "DEPLOYED"
    ? "badge ok"
    : failed
      ? "badge danger"
      : "badge warning";
  elements.progressSummary.textContent = `${release.releaseId} · ${release.productVersion}`;

  elements.progressSteps.forEach((step, index) => {
    const status = step.querySelector("span");
    step.className = "";
    if (failed) {
      if (index === releaseProgressOrder.length - 1) {
        step.className = "failed";
        status.textContent = "失败";
      } else {
        status.textContent = "查看审计";
      }
    } else if (release.state === "DEPLOYED" || index < currentIndex) {
      step.className = "complete";
      status.textContent = "完成";
    } else if (index === currentIndex) {
      step.className = "current";
      status.textContent = "处理中";
    } else {
      status.textContent = "等待";
    }
  });
}

async function api(path, options = {}) {
  if (!state.token) {
    throw new Error("请先输入独立运维令牌");
  }
  const headers = new Headers(options.headers || {});
  headers.set("Authorization", `Bearer ${state.token}`);
  const response = await fetch(path, { ...options, headers });
  const text = await response.text();
  let payload = null;
  if (text) {
    try {
      payload = JSON.parse(text);
    } catch {
      throw new Error(`服务返回了不可解析的响应（HTTP ${response.status}）`);
    }
  }
  if (!response.ok) {
    throw new Error(payload?.message || `请求失败（HTTP ${response.status}）`);
  }
  return payload;
}

function selectRelease(release) {
  state.selected = release;
  state.plan = release.plan || null;
  elements.selectedRelease.textContent =
    `${release.releaseId} · ${releaseStateLabel(release.state)}`;
  elements.planButton.hidden = state.plan?.action === "DEPLOY_SIGNED_RELEASE";
  elements.planButton.disabled = release.state !== "VERIFIED" && !release.plan;
  renderPlan();
  renderProgress(release);
}

function renderPlan() {
  const plan = state.plan;
  elements.planDetails.replaceChildren();
  elements.planDetails.hidden = !plan;
  elements.confirmForm.hidden =
    !plan || state.selected?.state !== "AWAITING_CONFIRMATION";
  if (!plan) return;

  const values = [
    ["计划摘要", plan.planSha256],
    ["主机快照", plan.hostSnapshotSha256],
    ["发布包", plan.packageSha256],
    ["到期时间", plan.expiresAt]
  ];
  for (const [label, value] of values) {
    const term = document.createElement("dt");
    const detail = document.createElement("dd");
    term.textContent = label;
    detail.textContent = value;
    elements.planDetails.append(term, detail);
  }
}

function renderReleases(releases) {
  elements.releaseList.replaceChildren();
  if (!releases.length) {
    const empty = document.createElement("p");
    empty.className = "empty";
    empty.textContent = "尚无发布记录。";
    elements.releaseList.append(empty);
    return;
  }
  for (const release of releases) {
    const card = document.createElement("article");
    card.className = "release-card";
    const title = document.createElement("h3");
    title.textContent =
      `${release.productVersion} · ${releaseStateLabel(release.state)}`;
    const digest = document.createElement("p");
    digest.className = "mono";
    digest.textContent = release.packageSha256;
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = "选择";
    button.addEventListener("click", () => {
      selectRelease(release);
      syncReleaseTracking();
    });
    card.append(title, digest, button);
    elements.releaseList.append(card);
  }
}

async function refreshReleases() {
  const releases = await api("/api/v1/releases");
  renderReleases(releases);
  if (state.selected) {
    const selected = releases.find((release) => release.releaseId === state.selected.releaseId);
    if (selected) {
      selectRelease(selected);
    }
  }
  return releases;
}

function renderAudit(page) {
  elements.auditList.replaceChildren();
  if (!page.events.length) {
    const empty = document.createElement("li");
    empty.className = "empty";
    empty.textContent = "尚无审计事件。";
    elements.auditList.append(empty);
    return;
  }
  for (const event of page.events) {
    const item = document.createElement("li");
    item.className = "audit-event";
    const title = document.createElement("strong");
    title.textContent = `#${event.sequence} · ${event.eventType}`;
    const state = document.createElement("span");
    state.textContent = event.releaseId
      ? `${event.releaseId} · ${releaseStateLabel(event.state || "BINDING")}`
      : "独立审计事件";
    const digest = document.createElement("p");
    digest.className = "mono";
    digest.textContent = event.eventSha256;
    item.append(title, state, digest);
    elements.auditList.append(item);
  }
}

async function refreshAudit() {
  const page = await api("/api/v1/audit?after=0&limit=50");
  renderAudit(page);
}

function renderAgent(status) {
  const pending = `待处理 ${status.pendingJobs} 项`;
  if (status.state === "ONLINE") {
    elements.agentBadge.textContent = status.productionExecutionEnabled
      ? `受限 Agent 在线 · ${pending}`
      : `受限 Agent 在线（仅校验）· ${pending}`;
    elements.agentBadge.className = status.productionExecutionEnabled
      ? "badge ok"
      : "badge warning";
    return;
  }
  if (status.state === "STALE") {
    elements.agentBadge.textContent = `受限 Agent 心跳过期 · ${pending}`;
    elements.agentBadge.className = "badge warning";
    return;
  }
  elements.agentBadge.textContent = `受限 Agent 未连接 · ${pending}`;
  elements.agentBadge.className = "badge neutral";
}

const operationsLabels = Object.freeze({
  HEALTHY: "正常",
  DEGRADED: "需关注",
  DOWN: "异常",
  UNKNOWN: "未知",
  DISABLED: "未启用"
});

const operationsPlaceholders = Object.freeze([
  ["SYSTEM", "服务器资源", "CPU、JVM 内存与磁盘容量。"],
  ["SERVICES", "固定服务", "Backend、Caddy 与 ReleaseAgent。"],
  ["DATABASE", "数据库", "MySQL loopback 连通性与只读探测。"],
  ["LOGS", "日志", "固定日志异常计数，不返回原始内容。"]
]);

const metricLabels = Object.freeze({
  hostName: "主机名",
  osName: "操作系统",
  osArchitecture: "系统架构",
  cpuUsedPercent: "CPU",
  systemMemoryUsedPercent: "系统内存",
  systemMemoryAvailableGiB: "可用内存",
  diskUsedPercent: "磁盘",
  diskUsableGiB: "可用磁盘",
  jvmHeapUsedPercent: "控制台 JVM",
  availableProcessors: "逻辑处理器",
  runtimeUptimeSeconds: "控制台运行时长"
});

const percentageMetrics = new Set([
  "cpuUsedPercent",
  "systemMemoryUsedPercent",
  "diskUsedPercent",
  "jvmHeapUsedPercent"
]);

function displayMetricValue(name, value) {
  if (value === "unknown") return "等待有效采样";
  if (name === "systemMemoryAvailableGiB" || name === "diskUsableGiB") {
    return `${value} GiB`;
  }
  if (name === "runtimeUptimeSeconds") {
    const seconds = Number(value);
    if (Number.isFinite(seconds) && seconds >= 0) {
      const hours = Math.floor(seconds / 3600);
      const minutes = Math.floor((seconds % 3600) / 60);
      return `${hours} 小时 ${minutes} 分钟`;
    }
  }
  return String(value);
}

function renderMetric(name, value) {
  const labelText = metricLabels[name] || name;
  const metric = document.createElement("div");
  metric.className = "metric";
  if (percentageMetrics.has(name)) {
    const numeric = Number(value);
    if (Number.isFinite(numeric) && numeric >= 0 && numeric <= 100) {
      metric.classList.add("metric-chart");
      const heading = document.createElement("div");
      heading.className = "metric-heading";
      const label = document.createElement("span");
      label.textContent = labelText;
      const amount = document.createElement("strong");
      amount.textContent = `${numeric}%`;
      heading.append(label, amount);
      const bar = document.createElement("div");
      bar.className = "metric-bar";
      bar.setAttribute("role", "progressbar");
      bar.setAttribute("aria-label", `${labelText} 使用率 ${numeric}%`);
      bar.setAttribute("aria-valuemin", "0");
      bar.setAttribute("aria-valuemax", "100");
      bar.setAttribute("aria-valuenow", String(numeric));
      const fill = document.createElement("span");
      fill.className = "metric-bar-fill";
      fill.style.width = `${numeric}%`;
      bar.append(fill);
      metric.append(heading, bar);
      return metric;
    }
  }
  const label = document.createElement("span");
  label.className = "metric-label";
  label.textContent = labelText;
  const amount = document.createElement("strong");
  amount.textContent = displayMetricValue(name, value);
  metric.append(label, amount);
  return metric;
}

function renderOperationsPlaceholders() {
  for (const [kindName, titleText, summaryText] of operationsPlaceholders) {
    const card = document.createElement("article");
    card.className = "operation-card placeholder";
    card.dataset.operationsPlaceholder = kindName.toLowerCase();
    const kind = document.createElement("span");
    kind.className = "component-kind";
    kind.textContent = `${kindName} · 待接入`;
    const title = document.createElement("h3");
    title.textContent = titleText;
    const summary = document.createElement("p");
    summary.textContent = summaryText;
    card.append(kind, title, summary);
    elements.operationsList.append(card);
  }
}

function renderOperations(dashboard) {
  const snapshot = dashboard.snapshot;
  const overall = snapshot.overallStatus || "UNKNOWN";
  elements.operationsBadge.textContent = operationsLabels[overall] || overall;
  elements.operationsBadge.className = overall === "HEALTHY"
    ? "badge ok"
    : overall === "DOWN"
      ? "badge danger"
      : overall === "DEGRADED"
        ? "badge warning"
        : "badge neutral";
  elements.operationsSummary.textContent = snapshot.enabled
    ? `已采集 ${snapshot.components.length} 个固定监控项；自动修复仅执行白名单服务启动。`
    : "监控尚未启用；发布功能不受影响。";
  elements.operationsObservedAt.textContent = snapshot.observedAt
    ? `采集时间：${new Date(snapshot.observedAt).toLocaleString("zh-CN")}`
    : "尚未采集";
  elements.operationsList.replaceChildren();
  if (!snapshot.components.length) {
    renderOperationsPlaceholders();
  }
  for (const component of snapshot.components) {
    const card = document.createElement("article");
    card.className = `operation-card ${String(component.status).toLowerCase()}`;
    card.dataset.kind = component.kind;
    const kind = document.createElement("span");
    kind.className = "component-kind";
    kind.textContent = `${component.kind} · ${operationsLabels[component.status] || component.status}`;
    const title = document.createElement("h3");
    title.textContent = component.label;
    const summary = document.createElement("p");
    summary.textContent = component.summary;
    const metrics = document.createElement("div");
    metrics.className = "metrics";
    for (const [name, value] of Object.entries(component.metrics || {})) {
      metrics.append(renderMetric(name, value));
    }
    card.append(kind, title, summary, metrics);
    elements.operationsList.append(card);
  }

  elements.remediationList.replaceChildren();
  if (!snapshot.recentRemediations.length) {
    const empty = document.createElement("li");
    empty.className = "empty";
    empty.textContent = "尚无自动修复记录。";
    elements.remediationList.append(empty);
  }
  for (const item of [...snapshot.recentRemediations].reverse()) {
    const row = document.createElement("li");
    row.textContent = `${new Date(item.occurredAt).toLocaleString("zh-CN")} · ${item.action} · ${item.outcome} · ${item.summary}`;
    elements.remediationList.append(row);
  }

  const notifications = dashboard.notifications;
  elements.notificationState.replaceChildren();
  const headline = document.createElement("p");
  headline.textContent = notifications.enabled
    ? `已启用，共配置 ${notifications.recipients.length} 个接收方`
    : `未启用，共配置 ${notifications.recipients.length} 个接收方`;
  elements.notificationState.append(headline);
  for (const recipient of notifications.recipients) {
    const row = document.createElement("div");
    row.className = "notification-recipient";
    row.textContent = `${recipient.name} · ${recipient.channel} · ${recipient.enabled ? "启用" : "停用"}`;
    elements.notificationState.append(row);
  }
  if (notifications.lastDispatch) {
    const last = document.createElement("p");
    last.textContent = `最近发送：${notifications.lastDispatch.status}，API 接受 ${notifications.lastDispatch.acceptedRecipients}/${notifications.lastDispatch.configuredRecipients}`;
    elements.notificationState.append(last);
  }
}

async function refreshOperations(force = false) {
  const dashboard = await api(
    force ? "/api/v1/operations/refresh" : "/api/v1/operations/status",
    force ? { method: "POST" } : {}
  );
  renderOperations(dashboard);
}

function syncOperationsPolling() {
  if (state.operationsTimer) {
    window.clearInterval(state.operationsTimer);
    state.operationsTimer = null;
  }
  if (state.token && !document.hidden) {
    state.operationsTimer = window.setInterval(() => {
      refreshOperations(false).catch(() => {
        elements.operationsObservedAt.textContent = "自动刷新暂时失败；不会触发无限重试。";
      });
    }, 15000);
  }
}

async function refreshAgent() {
  renderAgent(await api("/api/v1/agent"));
}

async function pollSelectedRelease(releaseId) {
  const releases = await refreshReleases();
  const release = releases.find((candidate) => candidate.releaseId === releaseId);
  if (!release) {
    throw new Error("自动刷新时找不到已选择的发布记录");
  }
  if (state.selected?.releaseId !== releaseId) {
    return null;
  }
  await Promise.all([refreshAgent(), refreshAudit()]);
  if (isReleaseTerminal(release.state)) {
    if (release.state === "DEPLOYED") {
      showMessage(`版本 ${release.productVersion} 已完成生产部署。`, true);
    } else {
      showMessage(`版本 ${release.productVersion} 发布失败，请查看审计时间线。`);
    }
  }
  return release.state;
}

const releaseTracker = createReleaseTracker({
  intervalMs: 5000,
  poll: pollSelectedRelease,
  onError: () => showMessage("发布状态自动刷新暂时失败，将继续重试；现有状态未被修改。")
});

function syncReleaseTracking() {
  const release = state.selected;
  if (release && shouldTrackRelease({
    authenticated: Boolean(state.token),
    visible: !document.hidden,
    state: release.state
  })) {
    releaseTracker.start(release.releaseId);
    return;
  }
  releaseTracker.stop();
}

elements.authForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const candidateToken = elements.token.value;
  if (!candidateToken) return;

  state.token = candidateToken;
  elements.authState.textContent = "正在验证运维身份…";
  elements.authSubmitButton.disabled = true;
  try {
    const dashboard = await api("/api/v1/operations/status");
    renderOperations(dashboard);
    elements.token.value = "";
    elements.authState.textContent = "身份已接受；令牌未写入浏览器存储";
    showMessage("运维身份验证成功。", true);
    syncOperationsPolling();

    const optionalResults = await Promise.allSettled([
      refreshReleases(), refreshAudit(), refreshAgent()
    ]);
    if (optionalResults.some((result) => result.status === "rejected")) {
      showMessage("身份已接受，系统状态已显示；部分辅助栏目暂时无法读取。", true);
    }
  } catch (error) {
    releaseTracker.stop();
    state.token = "";
    elements.authState.textContent = "认证失败";
    showMessage(error.message);
  } finally {
    elements.authSubmitButton.disabled = false;
  }
});

elements.uploadForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const file = elements.deploymentBundle.files[0];
  if (!file) return;
  const form = new FormData();
  form.append("bundle", file, file.name);
  try {
    showMessage("正在上传部署授权包并执行固定校验……", true);
    const release = await api("/api/v1/releases/import-bundle", {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey("import-bundle") },
      body: form
    });
    selectRelease(release);
    await refreshReleases();
    syncReleaseTracking();
    showMessage(`部署授权包 ${release.releaseId} 已验证，请执行一次最终确认。`, true);
  } catch (error) {
    showMessage(error.message);
  }
});

elements.planButton.addEventListener("click", async () => {
  try {
    state.plan = await api(`/api/v1/releases/${encodeURIComponent(state.selected.releaseId)}/plan`, {
      method: "POST"
    });
    renderPlan();
    await refreshReleases();
    syncReleaseTracking();
    showMessage("计划已绑定当前主机快照，请核对摘要后确认。", true);
  } catch (error) {
    showMessage(error.message);
  }
});

elements.confirmForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  try {
    const result = await api(
      `/api/v1/releases/${encodeURIComponent(state.selected.releaseId)}/confirm`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Idempotency-Key": idempotencyKey("confirm")
        },
        body: JSON.stringify({
          expectedPlanSha256: state.plan.planSha256,
          reason: elements.reason.value
        })
      }
    );
    elements.reason.value = "";
    await refreshReleases();
    syncReleaseTracking();
    showMessage(
      result.state === "QUEUED"
        ? `确认完成，耐久作业 ${result.jobId} 已排队。`
        : `已记录 ${result.approvals}/${result.requiredApprovals} 个确认。`,
      true
    );
  } catch (error) {
    showMessage(error.message);
  }
});

elements.refreshButton.addEventListener("click", () => {
  Promise.all([refreshReleases(), refreshAgent()])
    .then(() => syncReleaseTracking())
    .catch((error) => showMessage(error.message));
});

elements.auditRefreshButton.addEventListener("click", () => {
  refreshAudit().catch((error) => showMessage(error.message));
});

elements.operationsRefreshButton.addEventListener("click", () => {
  refreshOperations(true)
    .then(() => showMessage("系统运行状态已完成一次固定检查。", true))
    .catch((error) => showMessage(error.message));
});

document.addEventListener("visibilitychange", () => {
  syncReleaseTracking();
  syncOperationsPolling();
});
window.addEventListener("beforeunload", () => {
  releaseTracker.stop();
  if (state.operationsTimer) window.clearInterval(state.operationsTimer);
});

fetch("/actuator/health")
  .then((response) => {
    elements.healthBadge.textContent = response.ok ? "控制面在线" : "控制面异常";
    elements.healthBadge.className = response.ok ? "badge ok" : "badge neutral";
  })
  .catch(() => {
    elements.healthBadge.textContent = "控制面不可达";
  });
