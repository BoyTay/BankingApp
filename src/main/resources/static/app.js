"use strict";

const apiBase = "/api/v1";
const $ = (id) => document.getElementById(id);
const state = { token: null, user: null, wallet: null, page: 0, totalPages: 0, reconciliationPage: 0, pendingTransfer: null, pendingGrant: null };
const money = (value) => new Intl.NumberFormat("vi-VN").format(value ?? 0);
const dateTime = (value) => value ? new Date(value).toLocaleString("vi-VN") : "—";

function message(value, error = false) {
  const notice = $("notice");
  notice.textContent = value;
  notice.classList.toggle("error", error);
  notice.hidden = !value;
  if (value) notice.scrollIntoView({ block: "nearest" });
}

async function request(path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (state.token) headers.set("Authorization", `Bearer ${state.token}`);
  if (options.body && !(options.body instanceof FormData)) headers.set("Content-Type", "application/json");
  const response = await fetch(apiBase + path, { ...options, headers });
  if (response.status === 401 && state.token) {
    signOut(false);
    throw Object.assign(new Error("Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại."), { status: 401 });
  }
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw Object.assign(new Error(body.message || `Yêu cầu thất bại (HTTP ${response.status}).`), { status: response.status });
  }
  if (response.status === 204) return null;
  return response;
}

async function json(path, options = {}) {
  const response = await request(path, options);
  return response?.json();
}

function formData(form) { return Object.fromEntries(new FormData(form)); }
function submitJson(path, body) { return json(path, { method: "POST", body: JSON.stringify(body) }); }

function authMode(mode) {
  const register = mode === "register";
  $("login-form").hidden = register;
  $("register-form").hidden = !register;
  $("show-login").classList.toggle("selected", !register);
  $("show-register").classList.toggle("selected", register);
  $("auth-message").textContent = "";
}

async function login(email, password) {
  const data = await submitJson("/auth/login", { email, password });
  state.token = data.accessToken;
  state.user = data.user;
  $("auth-view").hidden = true;
  $("workspace").hidden = false;
  $("user-actions").hidden = false;
  $("user-name").textContent = data.user.displayName;
  $("admin-tab").hidden = data.user.role !== "ADMIN";
  selectTab("overview");
  await refresh();
}

function signOut(callApi = true) {
  const token = state.token;
  if (callApi && token) fetch(apiBase + "/auth/logout", { method: "POST", headers: { Authorization: `Bearer ${token}` } }).catch(() => {});
  state.token = null;
  state.user = null;
  state.wallet = null;
  state.pendingTransfer = null;
  state.pendingGrant = null;
  state.reconciliationPage = 0;
  $("reconciliation-results").hidden = true;
  $("reconciliation-status").textContent = "Chưa chạy đối soát.";
  $("workspace").hidden = true;
  $("user-actions").hidden = true;
  $("auth-view").hidden = false;
  $("login-form").reset();
  $("register-form").reset();
  $("auth-message").textContent = "";
  message("");
}

function selectTab(name) {
  if (name === "admin" && state.user?.role !== "ADMIN") return;
  document.querySelectorAll("[data-tab]").forEach((button) => button.classList.toggle("active", button.dataset.tab === name));
  document.querySelectorAll("[data-panel]").forEach((panel) => panel.hidden = panel.dataset.panel !== name);
  message("");
  if (name === "history") loadHistory().catch((error) => message(error.message, true));
}

function cell(row, value, className = "") {
  const item = document.createElement("td");
  item.textContent = value;
  if (className) item.className = className;
  row.append(item);
}

function renderTransfers(target, items, includeBalance) {
  target.replaceChildren();
  if (!items.length) {
    const row = document.createElement("tr");
    const item = document.createElement("td");
    item.colSpan = includeBalance ? 5 : 4;
    item.className = "empty";
    item.textContent = "Chưa có giao dịch chuyển tiền.";
    row.append(item);
    target.append(row);
    return;
  }
  for (const transfer of items) {
    const row = document.createElement("tr");
    const incoming = transfer.direction === "INCOMING";
    cell(row, dateTime(transfer.createdAt));
    cell(row, incoming ? "Nhận tiền" : "Chuyển tiền");
    cell(row, incoming ? transfer.senderWalletCode : transfer.recipientWalletCode);
    cell(row, `${incoming ? "+" : "−"}${money(transfer.amountDong)} ₫`, `numeric ${incoming ? "positive" : "negative"}`);
    if (includeBalance) cell(row, `${money(transfer.myBalanceAfterDong)} ₫`, "numeric");
    target.append(row);
  }
}

async function refresh() {
  try {
    const [wallet, recent] = await Promise.all([json("/me/wallet"), json("/transfers?page=0&size=5")]);
    state.wallet = wallet;
    $("balance").textContent = money(wallet.balanceDong);
    $("wallet-code").textContent = wallet.walletCode;
    renderTransfers($("recent-body"), recent.items, false);
  } catch (error) { message(error.message, true); }
}

async function loadHistory() {
  const data = await json(`/transfers?page=${state.page}&size=20`);
  renderTransfers($("history-body"), data.items, true);
  state.totalPages = Math.ceil(data.totalItems / data.size);
  $("page-label").textContent = `Trang ${state.page + 1} / ${Math.max(state.totalPages, 1)}`;
  $("prev-page").disabled = state.page === 0;
  $("next-page").disabled = state.page + 1 >= state.totalPages;
}

async function loadReconciliation(page = 0) {
  const button = $("check-reconciliation");
  button.disabled = true;
  $("reconciliation-status").textContent = "Đang kiểm tra số dư...";
  $("reconciliation-status").classList.remove("negative");
  try {
    const report = await json(`/admin/reconciliation?page=${page}&size=20`);
    state.reconciliationPage = page;
    const mismatches = report.mismatchCount;
    $("reconciliation-status").textContent = mismatches === 0
      ? `Đã kiểm tra ${report.checkedWallets} ví lúc ${dateTime(report.checkedAt)}. Không có sai lệch.`
      : `Đã kiểm tra ${report.checkedWallets} ví lúc ${dateTime(report.checkedAt)}. Có ${mismatches} ví cần kiểm tra.`;
    $("reconciliation-status").classList.toggle("negative", mismatches > 0);
    const body = $("reconciliation-body");
    body.replaceChildren();
    for (const item of report.items) {
      const row = document.createElement("tr");
      cell(row, item.walletCode);
      cell(row, money(BigInt(item.actualBalanceDong)), "numeric");
      cell(row, money(BigInt(item.ledgerBalanceDong)), "numeric");
      cell(row, money(BigInt(item.differenceDong)), "numeric negative");
      body.append(row);
    }
    $("reconciliation-results").hidden = mismatches === 0;
    $("reconciliation-page").textContent = `Trang ${page + 1} / ${Math.max(1, Math.ceil(mismatches / report.size))}`;
    $("reconciliation-prev").disabled = page === 0;
    $("reconciliation-next").disabled = (page + 1) * report.size >= mismatches;
  } catch (error) {
    $("reconciliation-results").hidden = true;
    $("reconciliation-status").textContent = error.message;
    $("reconciliation-status").classList.add("negative");
  } finally {
    button.disabled = false;
  }
}

function pending(kind, value) {
  if (kind === "transfer") state.pendingTransfer = value;
  else state.pendingGrant = value;
  $(`${kind}-pending`).hidden = !value;
  $(`retry-${kind}`).hidden = !value;
}

async function sendMoney(kind, payload) {
  const path = kind === "transfer" ? "/transfers" : "/admin/grants";
  try {
    const result = await submitJson(path, payload);
    pending(kind, null);
    message(kind === "transfer" ? `Chuyển tiền thành công: ${money(result.amountDong)} ₫.` : `Đã cấp ${money(result.amountDong)} ₫ cho ví ${result.recipientWalletCode}.`);
    $(`${kind}-form`).reset();
    await refresh();
  } catch (error) {
    if (error.status && error.status < 500) pending(kind, null);
    message(error.message, true);
  }
}

$("show-login").addEventListener("click", () => authMode("login"));
$("show-register").addEventListener("click", () => authMode("register"));
$("login-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const { email, password } = formData(event.currentTarget);
  try { await login(email, password); }
  catch (error) { $("auth-message").textContent = error.message; }
});
$("register-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const { email, displayName, password } = formData(event.currentTarget);
  try { await submitJson("/auth/register", { email, displayName, password }); await login(email, password); }
  catch (error) { $("auth-message").textContent = error.message; }
});
$("logout").addEventListener("click", () => signOut());
$("refresh").addEventListener("click", refresh);
$("copy-wallet").addEventListener("click", async () => {
  try { await navigator.clipboard.writeText(state.wallet.walletCode); message("Đã sao chép mã ví."); }
  catch { message("Không thể sao chép tự động. Hãy chọn mã ví để sao chép.", true); }
});
document.querySelectorAll("[data-tab]").forEach((button) => button.addEventListener("click", () => selectTab(button.dataset.tab)));
document.querySelectorAll("[data-go]").forEach((button) => button.addEventListener("click", () => selectTab(button.dataset.go)));
$("reload-history").addEventListener("click", () => loadHistory().catch((error) => message(error.message, true)));
$("prev-page").addEventListener("click", () => { state.page--; loadHistory().catch((error) => message(error.message, true)); });
$("next-page").addEventListener("click", () => { state.page++; loadHistory().catch((error) => message(error.message, true)); });

$("transfer-form").addEventListener("submit", (event) => {
  event.preventDefault();
  if (state.pendingTransfer) { message("Hãy thử lại yêu cầu cũ trước khi tạo yêu cầu mới.", true); return; }
  const data = formData(event.currentTarget);
  const payload = { requestKey: crypto.randomUUID(), recipientWalletCode: data.recipientWalletCode.trim(), amountDong: Number(data.amountDong) };
  if (!confirm(`Chuyển ${money(payload.amountDong)} ₫ đến ví ${payload.recipientWalletCode}?`)) return;
  pending("transfer", payload);
  sendMoney("transfer", payload);
});
$("retry-transfer").addEventListener("click", () => { if (state.pendingTransfer) sendMoney("transfer", state.pendingTransfer); });

$("grant-form").addEventListener("submit", (event) => {
  event.preventDefault();
  if (state.pendingGrant) { message("Hãy thử lại yêu cầu cũ trước khi tạo yêu cầu mới.", true); return; }
  const data = formData(event.currentTarget);
  const payload = { requestKey: crypto.randomUUID(), recipientWalletCode: data.recipientWalletCode.trim(), amountDong: Number(data.amountDong), reason: data.reason.trim() };
  if (!confirm(`Cấp ${money(payload.amountDong)} ₫ cho ví ${payload.recipientWalletCode}?`)) return;
  pending("grant", payload);
  sendMoney("grant", payload);
});
$("retry-grant").addEventListener("click", () => { if (state.pendingGrant) sendMoney("grant", state.pendingGrant); });
$("check-reconciliation").addEventListener("click", () => loadReconciliation());
$("reconciliation-prev").addEventListener("click", () => loadReconciliation(state.reconciliationPage - 1));
$("reconciliation-next").addEventListener("click", () => loadReconciliation(state.reconciliationPage + 1));

$("import-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const data = new FormData(event.currentTarget);
  const format = data.get("format");
  data.delete("format");
  data.set("requestKey", crypto.randomUUID());
  try {
    const result = await json(`/expense-imports?format=${encodeURIComponent(format)}`, { method: "POST", body: data });
    message(`Đã nhập ${result.rowCount} khoản chi từ ${result.sourceName}.`);
    event.currentTarget.reset();
  } catch (error) { message(error.message, true); }
});

$("stats-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const data = formData(event.currentTarget);
  const params = new URLSearchParams(data);
  try {
    const result = await json(`/expense-stats?${params}`);
    const target = $("stats-result");
    target.replaceChildren();
    const total = document.createElement("p");
    total.textContent = `${result.totalCount} khoản chi · ${money(result.totalAmountDong)} ₫`;
    target.append(total);
    for (const item of result.items) {
      const row = document.createElement("div");
      row.className = "stat-row";
      const label = document.createElement("span");
      const amount = document.createElement("strong");
      label.textContent = item.key;
      amount.textContent = `${money(item.amountDong)} ₫`;
      row.append(label, amount);
      target.append(row);
    }
  } catch (error) { message(error.message, true); }
});

$("statement-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const data = formData(event.currentTarget);
  try {
    const response = await request(`/statements?${new URLSearchParams(data)}`);
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `statement-${data.from}-${data.to}.${data.format}`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    message("Đã tải sao kê.");
  } catch (error) { message(error.message, true); }
});

const today = new Date().toISOString().slice(0, 10);
const monthStart = today.slice(0, 7) + "-01";
for (const form of [$("stats-form"), $("statement-form")]) {
  form.elements.from.value = monthStart;
  form.elements.to.value = today;
}
