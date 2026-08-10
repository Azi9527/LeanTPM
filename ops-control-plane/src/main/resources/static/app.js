"use strict";

const state = {
  token: "",
  selected: null,
  plan: null
};

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
  healthBadge: document.querySelector("#healthBadge"),
  agentBadge: document.querySelector("#agentBadge"),
  message: document.querySelector("#message")
};

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
    button.addEventListener("click", () => selectRelease(release));
    card.append(title, digest, button);
    elements.releaseList.append(card);
  }
}

async function refreshReleases() {
  const releases = await api("/api/v1/releases");
  renderReleases(releases);
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

async function refreshAgent() {
  renderAgent(await api("/api/v1/agent"));
}

elements.authForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  state.token = elements.token.value;
  elements.token.value = "";
  try {
    await Promise.all([refreshReleases(), refreshAudit(), refreshAgent()]);
    elements.authState.textContent = "身份已接受；令牌未写入浏览器存储";
    showMessage("运维身份验证成功。", true);
  } catch (error) {
    state.token = "";
    elements.authState.textContent = "认证失败";
    showMessage(error.message);
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
    .catch((error) => showMessage(error.message));
});

elements.auditRefreshButton.addEventListener("click", () => {
  refreshAudit().catch((error) => showMessage(error.message));
});

fetch("/actuator/health")
  .then((response) => {
    elements.healthBadge.textContent = response.ok ? "控制面在线" : "控制面异常";
    elements.healthBadge.className = response.ok ? "badge ok" : "badge neutral";
  })
  .catch(() => {
    elements.healthBadge.textContent = "控制面不可达";
  });
