"use strict";

const apiBase = "/api/v1";
const $ = (id) => document.getElementById(id);
const state = { token: null, user: null, wallet: null, accounts: [], selectedAccountId: null, page: 0, totalPages: 0, reconciliationPage: 0, pendingTransfer: null, pendingGrant: null, importPreview: null, pendingImport: null, pendingAccountAction: null };
const money = (value) => new Intl.NumberFormat("vi-VN").format(value ?? 0);
const dateTime = (value) => value ? new Date(value).toLocaleString("vi-VN") : "—";

let noticeTimer = null;
function message(value, error = false) {
  const notice = $("notice");
  clearTimeout(noticeTimer);
  notice.textContent = value;
  notice.classList.toggle("error", error);
  notice.hidden = !value;
  // Errors stay until the next action; confirmations fade out on their own.
  if (value && !error) noticeTimer = setTimeout(() => { notice.hidden = true; }, 6000);
}

function iconNode(name, className = "icon icon-sm") {
  const ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg");
  svg.setAttribute("class", className);
  svg.setAttribute("aria-hidden", "true");
  const use = document.createElementNS(ns, "use");
  use.setAttribute("href", `#i-${name}`);
  svg.append(use);
  return svg;
}

// Light/dark theme: an explicit choice is stored; otherwise the system setting applies.
function currentTheme() {
  return document.documentElement.dataset.theme || (matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");
}
$("theme-toggle").addEventListener("click", () => {
  const next = currentTheme() === "dark" ? "light" : "dark";
  document.documentElement.dataset.theme = next;
  try { localStorage.setItem("theme", next); } catch { /* private mode: keep for this page only */ }
  drawBalanceChart();
});

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
  $("user-avatar").textContent = (data.user.displayName.trim()[0] || "?").toUpperCase();
  $("admin-tab").hidden = data.user.role !== "ADMIN";
  selectTab("overview");
  startNotificationPolling();
  await refresh();
}

function signOut(callApi = true) {
  stopNotificationPolling();
  destroyBalanceChart();
  const token = state.token;
  if (callApi && token) fetch(apiBase + "/auth/logout", { method: "POST", headers: { Authorization: `Bearer ${token}` } }).catch(() => {});
  state.token = null;
  state.user = null;
  state.wallet = null;
  state.accounts = [];
  state.selectedAccountId = null;
  state.pendingAccountAction = null;
  state.pendingTransfer = null;
  state.pendingGrant = null;
  state.pendingImport = null;
  clearImportPreview();
  lockImportForm(false);
  $("import-form").reset();
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
  if (name === "notifications") {
    loadNotifications().catch((error) => message(error.message, true));
    loadLowBalanceSetting().catch((error) => message(error.message, true));
  }
  if (name === "accounts") loadAccounts().catch((error) => message(error.message, true));
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
    const typeCell = document.createElement("td");
    const type = document.createElement("span");
    type.className = "tx-type";
    const badgeIcon = document.createElement("span");
    badgeIcon.className = `tx-icon ${incoming ? "tx-in" : "tx-out"}`;
    badgeIcon.append(iconNode(incoming ? "down" : "up"));
    type.append(badgeIcon, document.createTextNode(incoming ? "Nhận tiền" : "Chuyển tiền"));
    typeCell.append(type);
    row.append(typeCell);
    cell(row, incoming ? transfer.senderWalletCode : transfer.recipientWalletCode);
    cell(row, `${incoming ? "+" : "−"}${money(transfer.amountDong)} ₫`, `numeric ${incoming ? "positive" : "negative"}`);
    if (includeBalance) cell(row, `${money(transfer.myBalanceAfterDong)} ₫`, "numeric");
    target.append(row);
  }
}

let balanceChart = null;
let balancePoints = [];

function destroyBalanceChart() {
  balanceChart?.destroy();
  balanceChart = null;
  balancePoints = [];
}

function shortDateTime(value) {
  const date = new Date(value);
  const two = (n) => String(n).padStart(2, "0");
  return `${two(date.getDate())}/${two(date.getMonth() + 1)} ${two(date.getHours())}:${two(date.getMinutes())}`;
}

function withAlpha(hex, alpha) {
  const value = /^#([0-9a-f]{6})$/i.exec(hex)?.[1];
  if (!value) return hex;
  const channel = (start) => parseInt(value.slice(start, start + 2), 16);
  return `rgba(${channel(0)}, ${channel(2)}, ${channel(4)}, ${alpha})`;
}

function cssVar(name) { return getComputedStyle(document.documentElement).getPropertyValue(name).trim(); }

function drawBalanceChart() {
  const canvas = $("balance-chart");
  const empty = $("balance-chart-empty");
  balanceChart?.destroy();
  balanceChart = null;
  empty.hidden = balancePoints.length > 0;
  canvas.hidden = balancePoints.length === 0;
  if (!balancePoints.length || typeof Chart === "undefined") return;
  const color = cssVar("--primary") || "#162b53";
  const context = canvas.getContext("2d");
  const gradient = context.createLinearGradient(0, 0, 0, canvas.clientHeight || 240);
  gradient.addColorStop(0, withAlpha(color, 0.32));
  gradient.addColorStop(1, withAlpha(color, 0));
  balanceChart = new Chart(context, {
    type: "line",
    data: {
      labels: balancePoints.map((point) => point.label),
      datasets: [{ data: balancePoints.map((point) => point.balance), borderColor: color, backgroundColor: gradient, fill: true, tension: 0.3, pointRadius: 3, pointHoverRadius: 5, borderWidth: 2.5 }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      animation: { duration: 450 },
      interaction: { mode: "index", intersect: false },
      plugins: { legend: { display: false }, tooltip: { callbacks: { label: (item) => `Số dư: ${money(item.parsed.y)} ₫` } } },
      scales: {
        x: { grid: { display: false }, ticks: { color: cssVar("--muted"), maxTicksLimit: 6 } },
        y: { grid: { color: cssVar("--border") }, ticks: { color: cssVar("--muted"), callback: (value) => money(value) } }
      }
    }
  });
}

function renderOverviewStats(items, totalItems) {
  const now = new Date();
  const monthStart = new Date(now.getFullYear(), now.getMonth(), 1);
  let inflow = 0;
  let outflow = 0;
  for (const transfer of items) {
    if (new Date(transfer.createdAt) < monthStart) continue;
    if (transfer.direction === "INCOMING") inflow += transfer.amountDong; else outflow += transfer.amountDong;
  }
  // Only the latest 100 transfers are loaded; flag the figure when the month may extend past them.
  const partial = totalItems > items.length && items.length > 0 && new Date(items[items.length - 1].createdAt) >= monthStart;
  $("kpi-in").textContent = `${partial ? "≥ " : ""}${money(inflow)} ₫`;
  $("kpi-out").textContent = `${partial ? "≥ " : ""}${money(outflow)} ₫`;
  balancePoints = items.slice(0, 20).reverse().map((transfer) => ({
    label: shortDateTime(transfer.createdAt),
    balance: transfer.myBalanceAfterDong
  }));
  drawBalanceChart();
}

async function refresh() {
  try {
    const [wallet, recent, accounts] = await Promise.all([json("/me/wallet"), json("/transfers?page=0&size=100"), json("/me/accounts")]);
    state.wallet = wallet;
    state.accounts = accounts;
    renderAccountSelectors();
    $("balance").textContent = money(wallet.balanceDong);
    $("wallet-code").textContent = wallet.walletCode;
    $("kpi-accounts").textContent = String(accounts.filter((account) => account.status === "ACTIVE").length);
    renderTransfers($("recent-body"), recent.items.slice(0, 5), false);
    renderOverviewStats(recent.items, recent.totalItems);
    refreshUnread().catch(() => {});
  } catch (error) { message(error.message, true); }
}

async function loadHistory() {
  const accountId = $("history-account")?.value;
  const data = await json(`/transfers?page=${state.page}&size=20${accountId ? `&accountId=${encodeURIComponent(accountId)}` : ""}`);
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
  const payload = { requestKey: crypto.randomUUID(), recipientWalletCode: data.recipientWalletCode.trim(), amountDong: Number(data.amountDong), sourceAccountId: data.sourceAccountId };
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

let importPreviewVersion = 0;
let importSending = false;

function lockImportForm(locked) {
  $("import-form").querySelectorAll("input, button").forEach((control) => { control.disabled = locked; });
  $("import-pending").hidden = !locked || importSending;
  $("retry-import").hidden = !locked || importSending;
}

function clearImportPreview() {
  importPreviewVersion++;
  state.importPreview = null;
  $("import-preview").hidden = true;
  $("confirm-import").hidden = true;
  $("import-valid-body").replaceChildren();
  $("import-error-list").replaceChildren();
}

function renderImportPreview(result) {
  $("import-preview").hidden = false;
  $("import-summary").textContent = `${result.sourceName}: ${result.rowCount} dòng dữ liệu, ${result.validRows.length} hợp lệ, ${result.errors.length} lỗi. ${result.canImport ? "Kiểm tra và xác nhận để lưu." : "Sửa lỗi rồi xem trước lại; chưa có dữ liệu nào được lưu."}`;
  const errors = $("import-error-list");
  errors.replaceChildren();
  for (const error of result.errors) {
    const item = document.createElement("li");
    item.textContent = error.sourceRow == null ? error.message : `Dòng ${error.sourceRow}: ${error.message.replace(/^Dòng \d+: /, "")}`;
    errors.append(item);
  }
  $("import-errors").hidden = !result.errors.length;
  const rows = $("import-valid-body");
  rows.replaceChildren();
  for (const expense of result.validRows) {
    const row = document.createElement("tr");
    cell(row, expense.sourceRow);
    cell(row, expense.spentOn);
    cell(row, expense.description);
    cell(row, expense.category);
    cell(row, money(expense.amountDong), "numeric");
    rows.append(row);
  }
  $("import-valid").hidden = !result.validRows.length;
  $("confirm-import").hidden = !result.canImport;
  $("import-summary").focus();
}

async function sendImport() {
  if (!state.pendingImport || importSending) return;
  importSending = true;
  lockImportForm(true);
  const { format, file, requestKey } = state.pendingImport;
  const data = new FormData();
  data.set("file", file);
  data.set("requestKey", requestKey);
  try {
    const result = await json(`/expense-imports?format=${encodeURIComponent(format)}`, { method: "POST", body: data });
    state.pendingImport = null;
    $("import-form").reset();
    clearImportPreview();
    message(`Đã nhập ${result.rowCount} khoản chi từ ${result.sourceName}.`);
  } catch (error) {
    if (error.status && error.status < 500) state.pendingImport = null;
    message(error.message, true);
  } finally {
    importSending = false;
    lockImportForm(Boolean(state.pendingImport));
  }
}

async function checkExpenseFile(file) {
  const firstLine = (await file.slice(0, 4096).text()).replace(/^\uFEFF/, "").split(/\r?\n/, 1)[0].trim();
  if (firstLine.startsWith("Thời gian UTC,") && firstLine.includes("Mã giao dịch")) {
    throw new Error("Đây là CSV sao kê chuyển tiền. Hãy dùng tệp mẫu chi tiêu trên trang.");
  }
  if (firstLine === "date,description,category,amount_vnd") {
    throw new Error("Đây là mẫu CSV cũ. Hãy tải tệp mẫu chi tiêu mới trên trang.");
  }
}

$("import-form").addEventListener("change", () => { if (!state.pendingImport) clearImportPreview(); });
$("import-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  if (state.pendingImport) return;
  clearImportPreview();
  const version = importPreviewVersion;
  const file = event.currentTarget.elements.file.files[0];
  if (!file) return;
  const data = new FormData();
  data.set("file", file);
  $("preview-import").disabled = true;
  try {
    await checkExpenseFile(file);
    const format = "SAMPLE_B";
    const result = await json(`/expense-imports/preview?format=${encodeURIComponent(format)}`, { method: "POST", body: data });
    if (version !== importPreviewVersion) return;
    state.importPreview = { format, file, result };
    renderImportPreview(result);
    message("");
  } catch (error) { if (version === importPreviewVersion) message(error.message, true); }
  finally { $("preview-import").disabled = false; }
});
$("confirm-import").addEventListener("click", () => {
  if (state.pendingImport || !state.importPreview?.result.canImport) return;
  const { format, file } = state.importPreview;
  if (file !== $("import-form").elements.file.files[0]) {
    clearImportPreview();
    message("Tệp đã thay đổi. Hãy xem trước lại.", true);
    return;
  }
  state.pendingImport = { format, file, requestKey: crypto.randomUUID() };
  $("confirm-import").hidden = true;
  sendImport();
});
$("retry-import").addEventListener("click", sendImport);

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

$("statement-scope").addEventListener("change", () => {
  const paged = $("statement-scope").value === "page";
  $("statement-page-field").hidden = !paged;
  $("statement-form").elements.page.disabled = !paged;
});

$("statement-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const data = formData(event.currentTarget);
  const paged = data.scope === "page";
  delete data.scope;
  if (paged) {
    data.page = String(Number(data.page) - 1);
    data.size = "500";
  }
  try {
    const response = await request(`/statements?${new URLSearchParams(data)}`);
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `statement-${data.from}-${data.to}${paged ? `-page-${Number(data.page) + 1}` : ""}.${data.format}`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    message(paged ? `Đã tải phần ${Number(data.page) + 1}.${response.headers.get("X-Has-More") === "true" ? " Còn phần tiếp theo." : " Đây là phần cuối."}` : "Đã tải sao kê.");
  } catch (error) { message(error.message, true); }
});

const today = new Date().toISOString().slice(0, 10);
const monthStart = today.slice(0, 7) + "-01";
for (const form of [$("stats-form"), $("statement-form")]) {
  form.elements.from.value = monthStart;
  form.elements.to.value = today;
}

const accountNames = { CHECKING: "Thanh toán", SAVINGS: "Tiết kiệm", CREDIT: "Tín dụng mô phỏng" };
function addAccountSelect(form, id, label, name) {
  const wrapper = document.createElement("label");
  wrapper.textContent = label;
  const select = document.createElement("select");
  select.id = id;
  select.name = name;
  wrapper.append(select);
  form.prepend(wrapper);
  return select;
}
addAccountSelect($("transfer-form"), "transfer-source", "Tài khoản Thanh toán nguồn", "sourceAccountId");
addAccountSelect($("statement-form"), "statement-account", "Tài khoản", "accountId");
const historyLabel = document.createElement("label");
historyLabel.className = "account-filter";
historyLabel.textContent = "Tài khoản";
const historySelect = document.createElement("select");
historySelect.id = "history-account";
historyLabel.append(historySelect);
document.querySelector('[data-panel="history"]').prepend(historyLabel);

function fillSelect(select, accounts, includeClosed = false) {
  const previous = select.value;
  select.replaceChildren();
  for (const account of accounts) {
    if (!includeClosed && account.status !== "ACTIVE") continue;
    const option = document.createElement("option");
    option.value = account.accountId;
    option.textContent = `${accountNames[account.accountType]} · ${account.accountCode}${account.status === "CLOSED" ? " (đã đóng)" : ""}`;
    select.append(option);
  }
  if ([...select.options].some((option) => option.value === previous)) select.value = previous;
}

function renderAccountSelectors() {
  const checking = state.accounts.filter((account) => account.accountType === "CHECKING");
  fillSelect($("transfer-source"), checking);
  fillSelect($("savings-funding"), checking);
  fillSelect($("low-balance-account"), checking.filter((account) => account.status === "ACTIVE"));
  loadLowBalanceSetting().catch(() => {});
  fillSelect($("credit-repay-source"), checking);
  fillSelect($("history-account"), state.accounts, true);
  fillSelect($("statement-account"), state.accounts, true);
  const selected = state.accounts.find((account) => account.accountId === state.selectedAccountId);
  if (selected) renderAccountList();
}

function accountTerms() {
  const type = $("account-open-type").value;
  $("savings-open-fields").hidden = type !== "SAVINGS";
  $("account-open-terms").textContent = {
    CHECKING: "Phí duy trì 5.000 ₫/tháng, bắt đầu từ kỳ phí tiếp theo. Chỉ tài khoản Thanh toán được chuyển tiền.",
    SAVINGS: "Gửi tối thiểu 100.000 ₫, kỳ hạn 90 ngày, lãi minh họa 4%/năm. Rút trước hạn mất phí 0,5% tiền gốc và không có lãi.",
    CREDIT: "Hạn mức ban đầu 0 ₫; quản trị viên cấp hạn mức. Phí thường niên 20.000 ₫ từ kỳ phí đầu tiên. Tài khoản này chỉ ghi khoản sử dụng và hoàn trả, không chuyển tiền trực tiếp."
  }[type];
}
$("account-open-type").addEventListener("change", accountTerms);
accountTerms();

function renderAccountList() {
  const target = $("account-list");
  target.replaceChildren();
  for (const account of state.accounts) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "account-tile";
    button.dataset.type = account.accountType;
    button.setAttribute("aria-pressed", String(account.accountId === state.selectedAccountId));
    const title = document.createElement("strong");
    title.textContent = `${accountNames[account.accountType]}${account.isDefault ? " · mặc định" : ""}`;
    const code = document.createElement("span");
    code.textContent = account.accountCode;
    const amount = document.createElement("b");
    amount.textContent = `${account.accountType === "CREDIT" ? "Dư nợ " + money(account.balanceDong === 0 ? 0 : -account.balanceDong) : money(account.balanceDong)} ₫`;
    const status = document.createElement("small");
    status.textContent = account.status === "ACTIVE" ? "Đang hoạt động" : "Đã đóng";
    button.append(title, code, amount, status);
    button.addEventListener("click", () => selectAccount(account.accountId).catch((error) => message(error.message, true)));
    target.append(button);
  }
}

async function loadAccounts() {
  state.accounts = await json("/me/accounts");
  renderAccountSelectors();
  renderAccountList();
  if (state.selectedAccountId) await selectAccount(state.selectedAccountId);
}

async function selectAccount(accountId) {
  state.selectedAccountId = accountId;
  renderAccountList();
  const account = state.accounts.find((item) => item.accountId === accountId);
  if (!account) return;
  $("savings-withdraw-form").hidden = true;
  $("credit-spend-form").hidden = true;
  $("credit-repay-form").hidden = true;
  $("close-account").hidden = account.isDefault || account.status !== "ACTIVE" || account.accountType === "SAVINGS";
  $("credit-activity").replaceChildren();
  let detail = `${accountNames[account.accountType]} · ${account.accountCode} · ${account.status === "ACTIVE" ? "đang hoạt động" : "đã đóng"}. `;
  if (account.accountType === "SAVINGS") {
    const savings = await json(`/me/accounts/${accountId}/savings`);
    detail += `Gốc ${money(savings.principalDong)} ₫, đáo hạn ${savings.maturesOn}, lãi minh họa ${savings.annualRateBps / 100}%/năm.`;
    $("savings-withdraw-form").hidden = account.status !== "ACTIVE";
  } else if (account.accountType === "CREDIT") {
    const [credit, activity] = await Promise.all([json(`/me/accounts/${accountId}/credit`), json(`/me/accounts/${accountId}/credit/activity`)]);
    detail += `Hạn mức ${money(credit.limitDong)} ₫, dư nợ ${money(credit.debtDong)} ₫, còn dùng ${money(credit.availableDong)} ₫.`;
    $("credit-spend-form").hidden = account.status !== "ACTIVE";
    $("credit-repay-form").hidden = account.status !== "ACTIVE";
    const heading = document.createElement("h3");
    heading.textContent = "Hoạt động tín dụng gần đây";
    $("credit-activity").append(heading);
    for (const item of activity) {
      const line = document.createElement("p");
      line.textContent = `${dateTime(item.createdAt)} · ${item.type === "CHARGE" ? "Sử dụng" : item.type === "REPAYMENT" ? "Hoàn trả" : "Phí"} ${money(item.amountDong)} ₫ · dư nợ ${money(item.debtAfterDong)} ₫ · ${item.description}`;
      $("credit-activity").append(line);
    }
  } else detail += `Số dư ${money(account.balanceDong)} ₫.`;
  $("account-detail").textContent = detail;
  const fees = await json(`/me/accounts/${accountId}/fees`);
  $("account-fees").replaceChildren();
  if (fees.length) {
    const heading = document.createElement("h3");
    heading.textContent = "Phí tài khoản";
    $("account-fees").append(heading);
    for (const fee of fees) {
      const line = document.createElement("p");
      line.textContent = `${fee.feeCode === "CHECKING_MONTHLY" ? "Duy trì tháng" : fee.feeCode === "CREDIT_ANNUAL" ? "Thường niên" : "Rút trước hạn"} ${fee.periodStart}: ${money(fee.amountDong)} ₫ · ${fee.status === "PAID" ? "đã thu" : "chưa thu"}`;
      $("account-fees").append(line);
    }
  }
}

async function accountAction(path, payload, success) {
  if (state.pendingAccountAction) { message("Hãy thử lại yêu cầu cũ trước khi tạo yêu cầu mới.", true); return; }
  state.pendingAccountAction = { path, payload, success };
  await retryAccountAction();
}
async function retryAccountAction() {
  const pending = state.pendingAccountAction;
  if (!pending) return;
  try {
    await submitJson(pending.path, pending.payload);
    state.pendingAccountAction = null;
    $("retry-account-action").hidden = true;
    message(pending.success);
    await refresh();
    if (document.querySelector('[data-tab="accounts"]').classList.contains("active")) await loadAccounts();
  } catch (error) {
    if (error.status && error.status < 500) state.pendingAccountAction = null;
    $("retry-account-action").hidden = !state.pendingAccountAction;
    message(error.message, true);
  }
}
const retryAccountButton = document.createElement("button");
retryAccountButton.id = "retry-account-action";
retryAccountButton.type = "button";
retryAccountButton.className = "secondary";
retryAccountButton.textContent = "Thử lại yêu cầu tài khoản cũ";
retryAccountButton.hidden = true;
retryAccountButton.addEventListener("click", retryAccountAction);
document.querySelector('[data-panel="accounts"]').append(retryAccountButton);

$("account-open-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const data = formData(event.currentTarget);
  const payload = { requestKey: crypto.randomUUID(), type: data.type };
  if (data.type === "SAVINGS") { payload.fundingAccountId = data.fundingAccountId; payload.amountDong = Number(data.amountDong); }
  if (!confirm(`Mở tài khoản ${accountNames[data.type]}? ${$("account-open-terms").textContent}`)) return;
  await accountAction("/me/accounts", payload, "Đã mở tài khoản.");
});
$("savings-withdraw-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  if (!confirm("Tất toán khoản tiết kiệm? Nếu trước hạn, phí là 0,5% tiền gốc và không hưởng lãi.")) return;
  await accountAction(`/me/accounts/${state.selectedAccountId}/savings/withdraw`, { requestKey: crypto.randomUUID() }, "Đã tất toán tiết kiệm.");
});
$("credit-spend-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const data = formData(event.currentTarget);
  const payload = { requestKey: crypto.randomUUID(), amountDong: Number(data.amountDong), description: data.description.trim() };
  if (!confirm(`Ghi khoản sử dụng tín dụng ${money(payload.amountDong)} ₫?`)) return;
  await accountAction(`/me/accounts/${state.selectedAccountId}/credit/charges`, payload, "Đã ghi khoản sử dụng hạn mức.");
});
$("credit-repay-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const data = formData(event.currentTarget);
  const payload = { requestKey: crypto.randomUUID(), sourceAccountId: data.sourceAccountId, amountDong: Number(data.amountDong) };
  if (!confirm(`Hoàn trả ${money(payload.amountDong)} ₫ từ tài khoản Thanh toán?`)) return;
  await accountAction(`/me/accounts/${state.selectedAccountId}/credit/repayments`, payload, "Đã hoàn trả dư nợ.");
});
$("close-account").addEventListener("click", async () => {
  if (!confirm("Đóng tài khoản đã tất toán? Tài khoản đã đóng không thể mở lại.")) return;
  await accountAction(`/me/accounts/${state.selectedAccountId}/close`, { requestKey: crypto.randomUUID() }, "Đã đóng tài khoản.");
});
$("credit-limit-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const data = formData(event.currentTarget);
  if (!confirm(`Đặt hạn mức ${money(Number(data.limitDong))} ₫ cho tài khoản này?`)) return;
  try {
    await submitJson(`/admin/credit-accounts/${data.accountId.trim()}/limit`, { requestKey: crypto.randomUUID(), limitDong: Number(data.limitDong) });
    message("Đã cập nhật hạn mức tín dụng.");
  } catch (error) { message(error.message, true); }
});
$("history-account").addEventListener("change", () => { state.page = 0; loadHistory().catch((error) => message(error.message, true)); });

// --- Thông báo (Observer: máy chủ đẩy sự kiện vào bảng thông báo, giao diện chỉ đọc) ---
const notificationState = { page: 0, totalPages: 0, timer: null };
const notificationLook = { TRANSFER_SENT: ["up", "neg"], TRANSFER_RECEIVED: ["down", "pos"], GRANT_RECEIVED: ["down", "pos"], LOW_BALANCE: ["bell", "warn"], FEE_DUE: ["file", "warn"], CREDIT_DEBT: ["card", "neg"], SAVINGS_MATURING: ["history", "info"] };
const notificationTypes = { TRANSFER_SENT: "Chuyển tiền", TRANSFER_RECEIVED: "Nhận tiền", GRANT_RECEIVED: "Được cấp tiền", LOW_BALANCE: "Số dư thấp", FEE_DUE: "Phí chưa thu", CREDIT_DEBT: "Nhắc nợ", SAVINGS_MATURING: "Tiết kiệm đáo hạn" };

function setUnread(count) {
  $("kpi-unread").textContent = String(count);
  $("unread-badge").textContent = count > 99 ? "99+" : String(count);
  $("unread-badge").hidden = count === 0;
}

async function refreshUnread() {
  const page = await json("/notifications?page=0&size=1");
  setUnread(page.unread);
}

function startNotificationPolling() {
  stopNotificationPolling();
  refreshUnread().catch(() => {});
  notificationState.timer = setInterval(() => refreshUnread().catch(() => {}), 30000);
}

function stopNotificationPolling() {
  clearInterval(notificationState.timer);
  notificationState.timer = null;
  setUnread(0);
  notificationState.page = 0;
}

async function loadNotifications() {
  const page = await json(`/notifications?page=${notificationState.page}&size=10`);
  notificationState.totalPages = Math.max(1, Math.ceil(page.total / page.size));
  setUnread(page.unread);
  const list = $("notification-list");
  list.replaceChildren();
  $("notification-empty").hidden = page.items.length > 0;
  for (const item of page.items) {
    const row = document.createElement("li");
    row.className = item.read ? "read" : "unread";
    const [iconName, tone] = notificationLook[item.type] || ["bell", "info"];
    const badge = document.createElement("span");
    badge.className = `notif-icon tone-${tone}`;
    badge.append(iconNode(iconName, "icon"));
    const main = document.createElement("div");
    main.className = "notif-main";
    const title = document.createElement("strong");
    title.textContent = item.title;
    const body = document.createElement("p");
    body.textContent = item.body;
    const time = document.createElement("small");
    time.textContent = `${notificationTypes[item.type] || "Thông báo"} · ${dateTime(item.createdAt)}`;
    main.append(title, body, time);
    row.append(badge, main);
    if (!item.read) {
      const mark = document.createElement("button");
      mark.type = "button";
      mark.className = "text-button";
      mark.textContent = "Đánh dấu đã đọc";
      mark.addEventListener("click", async () => {
        try { await json(`/notifications/${item.id}/read`, { method: "POST" }); await loadNotifications(); } catch (error) { message(error.message, true); }
      });
      main.append(mark);
    }
    list.append(row);
  }
  $("notif-page-label").textContent = `Trang ${notificationState.page + 1}/${notificationState.totalPages}`;
  $("notif-prev").disabled = notificationState.page === 0;
  $("notif-next").disabled = notificationState.page + 1 >= notificationState.totalPages;
}

$("read-all").addEventListener("click", async () => {
  try { await json("/notifications/read-all", { method: "POST" }); await loadNotifications(); } catch (error) { message(error.message, true); }
});
$("notif-prev").addEventListener("click", () => { notificationState.page--; loadNotifications().catch((error) => message(error.message, true)); });
$("notif-next").addEventListener("click", () => { notificationState.page++; loadNotifications().catch((error) => message(error.message, true)); });

async function loadLowBalanceSetting() {
  const accountId = $("low-balance-account").value;
  const input = $("low-balance-form").elements.lowBalanceDong;
  if (!accountId) { input.value = ""; return; }
  const settings = await json(`/me/accounts/${accountId}/notification-settings`);
  if ($("low-balance-account").value === accountId) input.value = settings.lowBalanceDong;
}

$("low-balance-account").addEventListener("change", () => loadLowBalanceSetting().catch((error) => message(error.message, true)));

$("low-balance-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  try {
    const accountId = $("low-balance-account").value;
    if (!accountId) { message("Chưa có tài khoản Thanh toán.", true); return; }
    const value = Number($("low-balance-form").elements.lowBalanceDong.value);
    await json(`/me/accounts/${accountId}/notification-settings`, { method: "PUT", body: JSON.stringify({ lowBalanceDong: value }) });
    message(value === 0 ? "Đã tắt cảnh báo số dư thấp." : `Sẽ cảnh báo khi số dư dưới ${money(value)} ₫.`);
    refreshUnread().catch(() => {});
    await loadNotifications();
  } catch (error) { message(error.message, true); }
});
